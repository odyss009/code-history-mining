package history
import intellijeval.PluginUtil

import static CommitReaderGitTest.findJUnitProject
import static history.util.DateTimeUtil.dateTime

class CommitMunging_Playground {
	static playOnIt() {
		def jUnitProject = findJUnitProject()
		def commitReader = new CommitReader(jUnitProject, 1)
		def commitFilesMunger = new CommitFilesMunger(jUnitProject)
		def eventsSource = new SourceOfChangeEvents(commitReader, commitFilesMunger.&mungeCommit)

		PluginUtil.doInBackground({
			eventsSource.request(dateTime("10:00 01/03/2013"), dateTime("17:02 09/05/2013")) { changeEvents ->
				PluginUtil.show(changeEvents.groupBy{it.revision}.keySet().join("\n"))
			}
		}, {})
	}
}
