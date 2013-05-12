import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.ui.GridBag
import com.intellij.util.ui.UIUtil
import com.michaelbaranov.microba.calendar.DatePicker
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import history.ChangeEventsExtractor
import history.EventStorage
import history.SourceOfChangeEvents
import history.SourceOfChangeLists
import history.util.Measure
import http.HttpUtil

import javax.swing.*
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.text.SimpleDateFormat

import static com.intellij.openapi.util.io.FileUtil.writeToFile
import static com.intellij.util.text.DateFormatUtil.getDateFormat
import static history.util.Measure.measure
import static intellijeval.PluginUtil.*
import static java.awt.GridBagConstraints.*
import static java.lang.Integer.parseInt

String pathToTemplates = pluginPath + "/html"


registerAction("DeltaFloraPopup", "ctrl alt shift D") { AnActionEvent actionEvent ->
	JBPopupFactory.instance.createActionGroupPopup(
			"Delta Flora",
			new DefaultActionGroup().with {
				add(new AnAction("Grab Project History (on file level)") {
					@Override void actionPerformed(AnActionEvent event) {
						grabHistoryOf(event.project, false)
					}
				})
				add(new AnAction("Grab Project History (on method level)") {
					@Override void actionPerformed(AnActionEvent event) {
						grabHistoryOf(event.project, true)
					}
				})
				add(new Separator())

				def eventFiles = new File("${PathManager.pluginsPath}/delta-flora").listFiles(new FileFilter() {
					@Override boolean accept(File pathName) { pathName.name.endsWith(".csv") }
				})
				addAll(eventFiles.collect{ file -> createEventStorageActionGroup(file, pathToTemplates) })
				it
			},
			actionEvent.dataContext,
			JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
			true
	).showCenteredInCurrentWindow(actionEvent.project)
}

show("loaded DeltaFlora plugin")


static ActionGroup createEventStorageActionGroup(File file, String pathToTemplates) {
	String projectName = file.name.replace(".csv", "")
	def showInBrowser = { template, eventsToJson ->
		def filePath = "${PathManager.pluginsPath}/delta-flora/${projectName}.csv"
		def events = new EventStorage(filePath).readAllEvents { line, e -> log("Failed to parse line '${line}'") }
		def json = eventsToJson(events)
		def server = HttpUtil.loadIntoHttpServer(projectName, pathToTemplates, template, json)
		BrowserUtil.launchBrowser("http://localhost:${server.port}/$template")
	}

	new DefaultActionGroup(file.name, true).with {
		add(new AnAction("Change Size Calendar View") {
			@Override void actionPerformed(AnActionEvent event) {
				doInBackground("Creating calendar view", {
					showInBrowser("calendar_view.html", Analysis.&createJsonForCalendarView)
				}, {})
			}
		})
		add(new AnAction("Change Size History") {
			@Override void actionPerformed(AnActionEvent event) {
				doInBackground("Creating change size history", {
					showInBrowser("changes_size_chart.html", Analysis.&createJsonForBarChartView)
				}, {})
			}
		})
		add(new AnAction("Files In The Same Commit Graph") {
			@Override void actionPerformed(AnActionEvent event) {
				doInBackground("Files in the same commit graph", {
					showInBrowser("cooccurrences-graph.html", Analysis.&createJsonForCooccurrencesGraph)
				}, {})
			}
		})
		add(new AnAction("Changes By Package Tree Map") {
			@Override void actionPerformed(AnActionEvent event) {
				doInBackground("Changes By Package Tree Map", {
					showInBrowser("treemap.html", Analysis.&createJsonForChangeSizeTreeMap)
				}, {})
			}
		})
		add(new AnAction("Commit Messages Word Cloud") {
			@Override void actionPerformed(AnActionEvent event) {
				doInBackground("Commit Messages Word Cloud", {
					showInBrowser("wordcloud.html", Analysis.&createJsonForCommitCommentWordCloud)
				}, {})
			}
		})
		add(new Separator())
		add(new AnAction("Delete") {
			@Override void actionPerformed(AnActionEvent event) {
				int userAnswer = Messages.showOkCancelDialog("Delete ${file.name}?", "Delete File", "&Delete", "&Cancel", UIUtil.getQuestionIcon());
				if (userAnswer == Messages.OK) file.delete()
			}
		})
		it
	}
}

SourceOfChangeEvents sourceOfChangeEventsFor(Project project, boolean extractEventsOnMethodLevel) {
	def vcsRequestBatchSizeInDays = 1
	def sourceOfChangeLists = new SourceOfChangeLists(project, vcsRequestBatchSizeInDays)
	def extractEvents = (extractEventsOnMethodLevel ?
		new ChangeEventsExtractor(project).&changeEventsFrom :
		new ChangeEventsExtractor(project).&fileChangeEventsFrom
	)
	new SourceOfChangeEvents(sourceOfChangeLists, extractEvents)
}

def grabHistoryOf(Project project, boolean extractEventsOnMethodLevel) {
	def sourceOfChangeEvents = sourceOfChangeEventsFor(project, extractEventsOnMethodLevel)

	def state = DialogState.loadDialogStateFor(project, pluginPath) {
		def outputFile = project.name + (extractEventsOnMethodLevel ? "-events.csv" : "-events-min.csv")
		def outputFilePath = "${PathManager.pluginsPath}/delta-flora/${outputFile}"
		new DialogState(new Date() - 300, new Date(), 1, outputFilePath)
	}
	showDialog(state, "Grab History Of Current Project", project) { DialogState userInput ->
		DialogState.saveDialogStateOf(project, pluginPath, userInput)

		doInBackground("Grabbing project history", { ProgressIndicator indicator ->
			measure("time") {
				def updateIndicatorText = { changeList, callback ->
					log(changeList.name)
					def date = dateFormat.format((Date) changeList.commitDate)
					indicator.text = "Grabbing project history (${date} - '${changeList.comment.trim()}')"

					callback()

					indicator.text = "Grabbing project history (${date} - looking for next commit...)"
				}
				def storage = new EventStorage(userInput.outputFilePath)
				def appendToStorage = { batchOfChangeEvents -> storage.appendToEventsFile(batchOfChangeEvents) }
				def prependToStorage = { batchOfChangeEvents -> storage.prependToEventsFile(batchOfChangeEvents) }

				if (storage.hasNoEvents()) {
					log("Loading project history from ${userInput.from} to ${userInput.to}")
					sourceOfChangeEvents.request(userInput.from, userInput.to, indicator, updateIndicatorText, appendToStorage)

				} else {
					def historyStart = storage.mostRecentEventTime
					def historyEnd = userInput.to
					log("Loading project history from $historyStart to $historyEnd")
					sourceOfChangeEvents.request(historyStart, historyEnd, indicator, updateIndicatorText, prependToStorage)

					historyStart = userInput.from
					historyEnd = storage.oldestEventTime
					log("Loading project history from $historyStart to $historyEnd")
					sourceOfChangeEvents.request(historyStart, historyEnd, indicator, updateIndicatorText, appendToStorage)
				}

				showInConsole("Saved change events to ${storage.filePath}", "output", project)
				showInConsole("(it should have history from '${storage.oldestEventTime}' to '${storage.mostRecentEventTime}')", "output", project)
			}
			Measure.durations.entrySet().collect{ "Total " + it.key + ": " + it.value }.each{ log(it) }
		}, {})
	}
}

@groovy.transform.Immutable
class DialogState {
	Date from
	Date to
	int vcsRequestBatchSizeInDays
	String outputFilePath

	static DialogState loadDialogStateFor(Project project, String pathToFolder, Closure<DialogState> createDefault) {
		def stateByProject = loadStateByProject(pathToFolder)
		def result = stateByProject.get(project.name)
		result != null ? result : createDefault()
	}

	static saveDialogStateOf(Project project, String pathToFolder, DialogState dialogState) {
		def stateByProject = loadStateByProject(pathToFolder)
		stateByProject.put(project.name, dialogState)
		writeToFile(new File(pathToFolder + "/dialog-state.json"), JsonOutput.toJson(stateByProject))
	}

	private static Map<String, DialogState> loadStateByProject(String pathToFolder) {
		try {
			def parseDate = { new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").parse(it) }
			def toDialogState = { map -> new DialogState(
					parseDate(map.from),
					parseDate(map.to),
					parseInt((String) map.vcsRequestBatchSizeInDays),
					map.outputFilePath
			)}

			def json = FileUtil.loadFile(new File(pathToFolder + "/dialog-state.json"))
			new JsonSlurper().parseText(json).collectEntries{ [it.key, toDialogState(it.value)] }
		} catch (IOException ignored) {
			[:]
		}
	}
}

def showDialog(DialogState state, String dialogTitle, Project project, Closure onOkCallback) {
	def fromDatePicker = new DatePicker(state.from, dateFormat.delegate)
	def toDatePicker = new DatePicker(state.to, dateFormat.delegate)
	def vcsRequestSizeField = new JTextField(String.valueOf(state.vcsRequestBatchSizeInDays))
	def filePathTextField = new TextFieldWithBrowseButton()

	JPanel rootPanel = new JPanel().with{
		layout = new GridBagLayout()
		GridBag bag = new GridBag()
				.setDefaultAnchor(0, EAST)
				.setDefaultAnchor(1, WEST)
				.setDefaultWeightX(1, 1)
				.setDefaultFill(HORIZONTAL)
		bag.defaultInsets = new Insets(5, 5, 5, 5)

		add(new JLabel("From:"), bag.nextLine().next())
		add(fromDatePicker, bag.next())
		add(new JLabel("To:"), bag.next())
		add(toDatePicker, bag.next())
		add(new JLabel("VCS Request batch size:"), bag.nextLine().next())
		add(vcsRequestSizeField, bag.next())
		add(new JLabel("day(s)"), bag.next().coverLine())
		add(new JLabel("File path:"), bag.nextLine().next())
		def actionListener = new ActionListener() {
			@Override void actionPerformed(ActionEvent e) {
				def csvFileType = FileTypeManager.instance.getFileTypeByExtension("csv")
				FileChooserDescriptor chooserDescriptor = FileChooserDescriptorFactory.createSingleFileDescriptor(csvFileType).with{
					showFileSystemRoots = true
					title = "Output File"
					description = "Select output file"
					hideIgnored = false
					it
				}
				VirtualFile file = FileChooser.chooseFile(chooserDescriptor, project, VirtualFileManager.instance.findFileByUrl("file://" + filePathTextField.text))
				if (file != null) filePathTextField.text = file.path
			}
		}
		filePathTextField.addActionListener(actionListener)
		filePathTextField.text = state.outputFilePath
		add(filePathTextField, bag.next().coverLine())


		def text = "(Please note that grabbing history might significantly slow down UI and/or take a really long time for a big project)"
		def textArea = new JTextArea(text).with {
			lineWrap = true
			wrapStyleWord = true
			editable = false
			it
		}
		textArea.background = background
		add(textArea, bag.nextLine().coverLine())
		it
	}

	DialogBuilder builder = new DialogBuilder(project)
	builder.title = dialogTitle
	builder.okActionEnabled = true
	builder.okOperation = {
		def toInteger = {
			String s = it.replaceAll("\\D", "")
			s.empty ? 1 : s.toInteger()
		}
		onOkCallback(new DialogState(fromDatePicker.date, toDatePicker.date, toInteger(vcsRequestSizeField.text), filePathTextField.text))
		builder.dialogWrapper.close(0)
	} as Runnable
	builder.centerPanel = rootPanel

	ApplicationManager.application.invokeLater{ builder.showModal(true) } as Runnable
}

