package com.morpheusdata.report.instances

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.json.JsonOutput
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.util.logging.Slf4j

import java.sql.Connection
import java.time.LocalDate
import java.time.format.DateTimeFormatter


@Slf4j
class UserProvisioningHistoryReportProvider extends AbstractReportProvider{
	protected MorpheusContext morpheusContext
	protected Plugin plugin

	UserProvisioningHistoryReportProvider(Plugin plugin, MorpheusContext morpheusContext) {
		this.morpheusContext = morpheusContext
		this.plugin = plugin
	}
	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	/**
	 * Returns the instance of the Plugin class that this provider is loaded from
	 * @return Plugin class contains references to other providers
	 */
	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		return "user-provisioning-history"
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return "User Provisioning History"
	}

	@Override
	ServiceResponse validateOptions(Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * The primary entrypoint for generating a report. This method can be a long running process that queries data in the database
	 * or from another external source and generates {@link ReportResultRow} objects that can be pushed into the database
	 *
	 * @param reportResult the Report result the data is being attached to. Status of the run is updated here, also this object contains filter parameters
	 *                     that may have been applied based on the {@link UserProvisioningHistoryReportProvider#getOptionTypes()}
	 */
	@Override
	void process(ReportResult reportResult) {
		morpheus.async.report.updateReportResultStatus(reportResult,ReportResult.Status.generating).blockingAwait()

		Long displayOrder = 0
		List<GroovyRowResult> instanceCountsPerUser = []
		Connection dbConnection

		try {
			dbConnection = morpheus.async.report.getReadOnlyDatabaseConnection().blockingGet()
			instanceCountsPerUser = new Sql(dbConnection).rows("""
				SELECT
					u.username,
					DATE_FORMAT(a.date_created, '%Y-%m') AS yearmonth,
					COUNT(*) AS instance_count
				FROM 
					audit_log a
				INNER JOIN 
					user u ON a.user_id = u.id
				WHERE
					a.description = 'Instance Created.'
					AND a.date_created >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 12 MONTH), '%Y-%m-01')
					AND a.date_created < DATE_FORMAT(CURDATE(), '%Y-%m-01')
				GROUP BY 
					u.username, yearmonth
				ORDER BY 
					instance_count DESC, u.username;	
			""")
		} finally {
			morpheus.async.report.releaseDatabaseConnection(dbConnection)
		}

		List<String> yearMonths = getYearMonthList()
		def reportPayload = [:]

		instanceCountsPerUser.each{ record ->
			if (!reportPayload.containsKey(record.username)) {
				// Create the key with an empty list or any initial value
				reportPayload[record.username] = [monthData: [], total: 0]
				yearMonths.each { ym ->
					reportPayload[record.username]["monthData"].add([yearMonth: ym, instCount: 0])
				}
			}
			reportPayload[record.username]["monthData"].find { it.yearMonth == record.yearMonth }?.instCount = record.instance_count
			reportPayload[record.username]["total"] += record.instance_count
		}

		// Convert the map to a list of objects with the required structure
		def reportList = reportPayload.collect { username, data ->
			[username: username, monthData: data.monthData, total: data.total]
		}

		// Sort the list by total in descending order
		reportList = reportList.sort { -it.total }

		Map<String,Object> data = [userJson: reportList, userJsonChart: JsonOutput.toJson(reportList), yearMonthList: yearMonths, yearMonthListChart: JsonOutput.toJson(yearMonths) ]
		ReportResultRow resultRowRecord = new ReportResultRow(section: ReportResultRow.SECTION_MAIN, displayOrder: displayOrder++, dataMap: data)
		morpheus.async.report.appendResultRows(reportResult,[resultRowRecord]).blockingGet()
		morpheus.async.report.updateReportResultStatus(reportResult,ReportResult.Status.ready).blockingAwait()
	}

	/**
	 * A short description of the report for the user to better understand its purpose.
	 * @return the description string
	 */
	@Override
	String getDescription() {
		return "View a history of the user provisioning"
	}

	/**
	 * Gets the category string for the report. Reports can be organized by category when viewing.
	 * @return the category string (i.e. inventory)
	 */
	@Override
	String getCategory() {
		return "inventory"
	}

	/**
	 * Only the owner of the report result can view the results.
	 * @return whether this report type can be read by the owning user only or not
	 */
	@Override
	Boolean getOwnerOnly() {
		return false
	}

	/**
	 * Some reports can only be run by the master tenant for security reasons. This informs Morpheus that the report type
	 * is a master tenant only report.
	 * @return whether or not this report is for the master tenant only.
	 */
	@Override
	Boolean getMasterOnly() {
		return false
	}

	/**
	 * Detects whether or not this report is scopable to all cloud types or not
	 * TODO: Implement this for custom reports (NOT YET USABLE)
	 * @return whether or not the report is supported by all cloud types. This allows for cloud type specific reports
	 */
	@Override
	Boolean getSupportsAllZoneTypes() {
		return true
	}

	@Override
	List<OptionType> getOptionTypes() {
		return null
	}

	/**
	 * Presents the HTML Rendered output of a report. This can use different {@link Renderer} implementations.
	 * The preferred is to use server side handlebars rendering with {@link com.morpheusdata.views.HandlebarsRenderer}
	 * @param reportResult the results of a report
	 * @param reportRowsBySection the individual row results by section (i.e. header, vs. data)
	 * @return result of rendering an template
	 */
	@Override
	HTMLResponse renderTemplate(ReportResult reportResult, Map<String, List<ReportResultRow>> reportRowsBySection) {
		ViewModel<String> model = new ViewModel<String>()
		def HashMap<String, String> reportPayload = new HashMap<String, String>();

		// Add web nonce to allow the use of javascript scripts
		def webnonce = morpheus.getWebRequest().getNonceToken()
		reportPayload.put("webnonce", webnonce)

		// Pass report data to the hbs render
		reportPayload.put("reportdata", reportRowsBySection)
		model.object = reportPayload
		getRenderer().renderTemplate("hbs/userProvisioningHistory", model)
	}


	//returns a list of the previous 12 months, current excluded
	static List<String> getYearMonthList() {
		List<String> yearMonthList = []

		// Define the current date and formatter for 'yyyy-MM'
		LocalDate currentDate = LocalDate.now()
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM")

		// Loop to generate the last 12 months
		(1..12).each { monthOffset ->
			def yearMonth = currentDate.minusMonths(monthOffset).format(formatter)
			yearMonthList << yearMonth.toString()
		}
		return yearMonthList.reverse()
	}
}
