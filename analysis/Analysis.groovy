package analysis
import groovy.time.TimeCategory
import events.CommitInfo
import events.FileChangeEvent
import events.FileChangeInfo

import java.text.SimpleDateFormat

import static analysis.Analysis.Util.*
import static java.util.concurrent.TimeUnit.*

class Analysis {
	static String createJsonForCommitsStackBarsChart(Collection<FileChangeEvent> events) {
		def fromDay = floorToDay(events.last().revisionDate)
		def toDay = floorToDay(events.first().revisionDate)
		def addMissingDays = { valuesByDate ->
			use(TimeCategory) {
				def day = fromDay.clone()
				while (!day.after(toDay)) {
					if (!valuesByDate.containsKey(day)) valuesByDate[day] = []
					day += 1.day
				}
				valuesByDate
			}
		}

		def changesByAuthorByDate = events
			.groupBy({ it.author }, { floorToDay(it.revisionDate) })
			.collectEntries {
				it.value = addMissingDays(it.value).sort()
				it
			}
		def amountOfCommitsByAuthor = changesByAuthorByDate.entrySet().collectEntries{
			def author = it.key
			def commits = it.value.values()
			[author, commits.size()]
		}
		def numberOfTopCommitters = 5
		def flattened = changesByAuthorByDate.entrySet().toList()
				.sort{ -amountOfCommitsByAuthor[it.key] }
				.take(numberOfTopCommitters)
				.collectMany { entry ->
					entry.value.collect { [it.key, entry.key, it.value.size()] }
				}
		asCsvStringLiteral(flattened, ["date", "author", "amount of commits"])
	}

	static String createJson_TimeBetweenCommits_Histogram(List<FileChangeEvent> events) {
		Collection.mixin(Util)

		def times = events
				.groupBy{it.revision}.values().collect{it.first()}
				.sort{it.revisionDate} // sort because VCS doesn't guarantee that commit time increases (e.g. observed in junit history)
				.groupBy{it.author}.values().collectMany{ commitsByAuthor ->
					commitsByAuthor.pairs().collect{ first, second -> second.revisionDate.time - first.revisionDate.time }
				}
				.findAll{it > MINUTES.toMillis(1)} // this is an attempt to exclude time diffs between merging pull requests

		asCsvStringLiteral(times, ["metric"])
	}

	static void createJson_AmountOfComitters_Chart(List<FileChangeEvent> events) {
		def comittersByDay = events
				.groupBy{ floorToDay(it.revisionDate) }
				.collectEntries{ [it.key, it.value.collect{it.author}.unique()]}
				.sort{ it.key }
		println(comittersByDay.entrySet().join("\n"))
	}

	static void createJson_AverageAmountOfLinesChangedByDay_Chart(List<FileChangeEvent> events) {
		def averageChangeSize = { eventList ->
			if (eventList.empty) 0
			else eventList.sum(0){changeSizeInLines(it)} / eventList.size()
		}
		def averageChangeSizeByDay = events
				.groupBy{ floorToDay(it.revisionDate) }
				.collectEntries{ [it.key, averageChangeSize(it.value)] }
				.sort{ it.key }
		println(averageChangeSizeByDay.entrySet().join("\n"))
	}

	static void createJson_AverageAmountOfFilesInCommitByDay_Chart(List<FileChangeEvent> events) {
		def amountOfFilesIn = { eventsList -> eventsList.collect{it.fileName + it.packageNameBefore + it.packageName}.unique().size() }
		def averageChangeSize = { eventsByRevision ->
			if (eventsByRevision.empty) 0
			else eventsByRevision.entrySet().sum(0){ amountOfFilesIn(it.value) } / eventsByRevision.size()
		}
		def changeSizeByDay = events
				.groupBy({floorToDay(it.revisionDate) }, {it.revision})
				.collectEntries{ [it.key, averageChangeSize(it.value)] }
				.sort{ it.key }
		println(changeSizeByDay.entrySet().join("\n"))
	}

	static void createJson_CommitsWithAndWithoutTests_Chart(List<FileChangeEvent> events) {
		Collection.mixin(Util)

		def withoutExtension = { String s -> s.contains(".") ? s.substring(0, s.lastIndexOf(".")) : s }
		def containUnitTests = { eventList -> eventList.any{ withoutExtension(it.fileName).endsWith("Test") } }
		def areRecentEnough = { eventList1, eventList2 ->
			long timeDiff = eventList1.first().revisionDate.time - eventList2.first().revisionDate.time
			HOURS.convert(timeDiff, MILLISECONDS) <= 1
		}

		// reverse events to go past-to-present and check past commit (looking for "test-first")
		def result = events.reverse()
				.groupBy{it.revision}
				.entrySet().pairs().collect{ pair ->
					def previousCommitEvents = pair[0].value
					def commitEvents = pair[1].value

					if (containUnitTests(commitEvents) || (containUnitTests(previousCommitEvents) && areRecentEnough(previousCommitEvents, commitEvents))) {
						[date: commitEvents.first().revisionDate, unitTests: true]
					} else {
						[date: commitEvents.first().revisionDate, unitTests: false]
					}
				}

		println(result.join("\n"))
	}

	static String createJson_AuthorConnectionsThroughChangedFiles_Graph(List<FileChangeEvent> events, int threshold = 7) {
		Collection.mixin(Util)

		events = useLatestNameForMovedFiles(events)

		def keepOneWeekOfEvents = { event, currentEvent ->
			long timeDiff = currentEvent.revisionDate.time - event.revisionDate.time
			DAYS.convert(timeDiff, MILLISECONDS) <= 7
		}
		def matchingEvents = events.reverse()
				.collectWithHistory(keepOneWeekOfEvents) { previousEvents, event ->
					def relatedEvents = previousEvents
							.findAll{ fullFileNameIn(it) == fullFileNameIn(event) && it.author != event.author }
					relatedEvents.empty ? null : [event: event, relatedEvents: relatedEvents]
				}
				.findAll{it != null}
				.groupBy{[author: it.event.author, fileName: fullFileNameIn(it.event)]}
				.findAll{it.value.size() >= threshold}
		def links = matchingEvents
				.collectEntries{[it.key, it.value.size()]}
				.sort{-it.value}

		def notInMatchingEvents = { event ->
			!matchingEvents.values().any{it.any{ it.event.revision == event.revision && fullFileNameIn(it.event) == fullFileNameIn(event) }}
		}
		def relatedLinks = matchingEvents.values()
				.inject([]){ result, entries -> result.addAll(entries.collectMany{it.relatedEvents}.unique()); result }.unique()
				.findAll{ notInMatchingEvents(it) }
				.groupBy{[author: it.author, fileName: fullFileNameIn(it)]}
				.collectEntries{[it.key, it.value.size()]}
				.sort{-it.value}

		def allLinks = relatedLinks.entrySet().inject(links) { map, entry ->
			if (map.containsKey(entry.key)) {
				map[entry.key] += entry.value
			} else {
				map.put(entry.key, entry.value)
			}
			map
		}

		def authors = allLinks.keySet().collect{it.author}.unique().toList()
		def files = allLinks.keySet().collect{it.fileName}.unique().toList()
		def nodesJSLiteral =
			(files.collect{'{"name": "' + it + '", "group": 1}'} +
			authors.collect{'{"name": "' + it + '", "group": 2}'}).join(",\n")

		def nodes = files + authors
		def relations = allLinks.entrySet().collect{ [nodes.indexOf(it.key.author), nodes.indexOf(it.key.fileName), it.value] }
		def relationsJSLiteral = relations.collect{'{"source": ' + it[0] + ', "target": ' + it[1] + ', "value": ' + it[2] + "}"}.join(",\n")

		'"nodes": [' + nodesJSLiteral + '],\n' + '"links": [' + relationsJSLiteral + ']'
	}

	static String createJson_CommitsByDayOfWeekAndTime_PunchCard(List<FileChangeEvent> events) {
		def amountOfCommitsByMinute = events
				.groupBy{it.revision}.entrySet()*.collect{it.value.first()}.flatten()
				.groupBy{[dayOfWeekOf(it.revisionDate), hourOf(it.revisionDate), minuteOf(it.revisionDate)]}
				.collectEntries{[it.key, it.value.size()]}
				.sort{a, b -> (a.key[0] * 10000 + a.key[1] * 100 + a.key[2]) <=> (b.key[0] * 10000 + b.key[1] * 100 + b.key[2]) }

		asCsvStringLiteral(
				amountOfCommitsByMinute.entrySet().collect{[it.key[0], it.key[1], it.key[2], it.value]},
				["dayOfWeek", "hour", "minute", "value"]
		)
	}

	static class TreeMapView {
		static String createJson_AmountOfChangeInFolders_TreeMap(List<FileChangeEvent> events) {
			def filePaths = useLatestNameForMovedFiles(events)
					.collect{ it.packageName + "/" + it.fileName }

			def containerTree = new Container("", 0)
			filePaths.inject(containerTree) { Container tree, filePath -> tree.updateTree(filePath) }
			containerTree.firstChild.toJSON()
		}

		private static class Container {
			private final String name
			private final Collection<Container> children
			private int commits

			Container(String name, Collection<Container> children = new ArrayList(), int commits) {
				this.name = name
				this.children = children.findAll{ it.commits > 0 }
				this.commits = commits
			}

			Container updateTree(String filePath) {
				doUpdateTree(filePath.split("/").toList(), this)
				this
			}

			private static doUpdateTree(List<String> filePath, Container container) {
				container.plusCommit()

				if (filePath.empty) return

				def matchingChild = container.children.find{ it.name == filePath.first() }
				if (matchingChild == null)
					matchingChild = container.addChild(new Container(filePath.first(), 0))

				doUpdateTree(filePath.tail(), matchingChild)
			}

			Container getFirstChild() { children.first() }

			private Container addChild(Container container) {
				children.add(container)
				container
			}

			private def plusCommit() {
				commits++
			}

			String toJSON() {
				String childrenAsJSON = "\"children\": [\n" + children.collect { it.toJSON() }.join(',\n') + "]"
				"{" +
						"\"name\": \"$name\", " +
						"\"size\": \"$commits\", " +
						childrenAsJSON +
						"}"
			}
		}
	}

	static String createJson_CommitComments_WordCloud(events) {
		def excludedWords = "also,as,at,but,by,for,from,in,into,of,off,on,onto,out,than,to,up,with,when," +
				"which,that,this,them,they,we,an,the,is,be,are,was,do,it,so,not,no,and".split(",").toList().toSet()
		def notExcluded = { String s -> s.length() > 1 && !excludedWords.contains(s) }

		Map wordOccurrences = events
				.groupBy{ it.revision }.entrySet()
				.collect{ it.value.first().commitMessage }
				.collectMany{
					it.split(/[\s!{}\[\]+-<>()\/\\,"'@&$=*\|\?]/)
						.collect{it.trim().toLowerCase()}.findAll(notExcluded)
				}
				.inject([:].withDefault{0}) { wordFrequency, word ->
					wordFrequency.put(word, wordFrequency[word] + 1)
					wordFrequency
				}
				.sort{ -it.value }

		def threshold = 1000 // empirical number
		if (wordOccurrences.size() > threshold)
			wordOccurrences = wordOccurrences.take(threshold)

		"""{"words": [
${wordOccurrences.collect { '{"text": "' + it.key + '", "size": ' + it.value + '}' }.join(",\n")}
]}
"""
	}

	static String createJson_FilesInTheSameCommit_Graph(List<FileChangeEvent> events, threshold = 7) {
		Collection.mixin(Util)

		def fileNamesByRevision = events
				.groupBy{ it.revision }
				.values()*.collect{ fullFileNameIn(it) }*.toList()*.unique()
		def pairCoOccurrences = fileNamesByRevision
				.inject([:].withDefault{0}) { map, fileNames -> fileNames.pairs().each{ map[it.sort()] += 1 }; map }
				.findAll{ it.value > threshold }.sort{-it.value}

		def nodes = pairCoOccurrences.keySet().flatten().unique().toList()
		def relations = pairCoOccurrences.entrySet().collect{ [nodes.indexOf(it.key[0]), nodes.indexOf(it.key[1]), it.value] }
		def nodesJSLiteral = nodes.collect{'{"name": "' + it + '", "group": 1}'}.join(",\n")
		def relationsJSLiteral = relations.collect{'{"source": ' + it[0] + ', "target": ' + it[1] + ', "value": ' + it[2] + "}"}.join(",\n")

		'"nodes": [' + nodesJSLiteral + '],\n' + '"links": [' + relationsJSLiteral + ']'
	}

	static String createJson_ChangeSize_Chart(List<FileChangeEvent> events) {
		def eventsByDay = events.groupBy{ floorToDay(it.revisionDate) }

		def commitsAmountByDate = eventsByDay.collect{ [it.key, it.value.groupBy{ it.revision }.size()] }.sort{it[0]}
		def totalChangeSizeInCharsByDate = eventsByDay.collect{ [it.key, it.value.sum{ changeSizeInChars(it) }] }.sort{it[0]}
		def totalChangeInLinesSizeByDate = eventsByDay.collect{ [it.key, it.value.sum{ changeSizeInLines(it) }] }.sort{it[0]}

		def changeSizeInCommits = asCsvStringLiteral(commitsAmountByDate, ["date", "changeSize"])
		def changeSizeInChars = asCsvStringLiteral(totalChangeSizeInCharsByDate, ["date", "changeSize"])
		def changeSizeInLines = asCsvStringLiteral(totalChangeInLinesSizeByDate, ["date", "changeSize"])

		"[$changeSizeInCommits,$changeSizeInLines,$changeSizeInChars]"
	}

	static String createJson_ChangeSize_Calendar(List<FileChangeEvent> events) {
		def changeSizeInChars = {
			def totalChangeSizeByDate = events
					.groupBy{ floorToDay(it.revisionDate) }
					.collectEntries{ [it.key, it.value.sum{ changeSizeInChars(it) }] }.sort{ it.key }
			def changesSizeRelativeToAll_ByDate = totalChangeSizeByDate.collect{ [it.key, it.value] }
			asCsvStringLiteral(changesSizeRelativeToAll_ByDate, ["date", "changeSize"])
		}()

		def changeSizeInLines = {
			def totalChangeSizeByDate = events
					.groupBy{ floorToDay(it.revisionDate) }
					.collectEntries{ [it.key, it.value.sum{ changeSizeInLines(it) }] }.sort{ it.key }
			def changesSizeRelativeToAll_ByDate = totalChangeSizeByDate.collect{ [it.key, it.value] }
			asCsvStringLiteral(changesSizeRelativeToAll_ByDate, ["date", "changeSize"])
		}()

		def changeSizeInCommits = {
			def commitsAmountByDate = events
					.groupBy{ it.revision }.collect{ it.value.first() }
					.groupBy{ floorToDay(it.revisionDate) }
					.collectEntries{ [it.key, it.value.size()] }.sort()
			def changesSizeRelativeToAll_ByDate = commitsAmountByDate.collect{ [it.key, it.value] }
			asCsvStringLiteral(changesSizeRelativeToAll_ByDate, ["date", "changeSize"])
		}()

		"[$changeSizeInCommits,$changeSizeInLines,$changeSizeInChars]"
	}

	static class Util {
		static List<FileChangeEvent> useLatestNameForMovedFiles(List<FileChangeEvent> events) {
			for (int i = 0; i < events.size(); i++) {
				def event = events[i]
				if (event.fileChangeType != "MOVED") continue

				def oldFileName = event.fileNameBefore
				def oldPackageName = event.packageNameBefore
				def newFileName = event.fileName
				def newPackageName = event.packageName

				for (int j = i + 1; j < events.size(); j++) {
					def thatEvent = events[j]
					if (thatEvent.fileName == oldFileName && thatEvent.packageName == oldPackageName) {
						def fileChangeType = thatEvent.fileChangeType
						if (thatEvent.fileChangeType == "MOVED") {
							oldFileName = thatEvent.fileNameBefore
							oldPackageName = thatEvent.packageNameBefore
							fileChangeType = "MOVED_UNDONE"
						}
						events[j] = updated(thatEvent, newFileName, newPackageName, fileChangeType)
					}
				}
			}
			events
		}

		private static FileChangeEvent updated(FileChangeEvent fileChangeEvent, String newFileName, String newPackageName,
		                                       String updatedChangeType) {
			fileChangeEvent.with{
				new FileChangeEvent(
						new CommitInfo(revision, author, revisionDate, commitMessage),
						new FileChangeInfo(newFileName, "", newPackageName, "", updatedChangeType, lines, chars)
				)
			}
		}
		
		static def changeSizeInChars(FileChangeEvent event) {
			if (event.chars == FileChangeInfo.NA || event.chars == FileChangeInfo.TOO_BIG_TO_DIFF) 0
			else event.chars.added + event.chars.modified + event.chars.removed
		}
		static def changeSizeInLines(FileChangeEvent event) {
			if (event.lines == FileChangeInfo.NA || event.lines == FileChangeInfo.TOO_BIG_TO_DIFF) 0
			else event.lines.added + event.lines.modified + event.lines.removed
		}

		static String asCsvStringLiteral(Collection values, List header) {
			def formatDate = { Date date -> new SimpleDateFormat("dd/MM/yyyy").format(date) }

			def jsNewLine = "\\n\\\n"
			def jsHeader = header.join(",") + jsNewLine
			def jsBody = values
					.collect{ it.collect{it instanceof Date ? formatDate(it) : it} }
					.collect{it.join(",")}
					.join(jsNewLine)
			"\"\\\n" + jsHeader + jsBody + jsNewLine + "\"";
		}

		static String fullFileNameIn(FileChangeEvent event) {
			event.packageName + "/" + event.fileName
		}

		static Date floorToDay(Date date) {
			date[Calendar.MILLISECOND] = 0
			date[Calendar.SECOND] = 0
			date[Calendar.MINUTE] = 0
			date[Calendar.HOUR_OF_DAY] = 0
			date
		}

		static int dayOfWeekOf(Date date) {
			int day = date.getAt(Calendar.DAY_OF_WEEK)
			// make it Monday-based (Monday=1, Tuesday=2 ... Sunday=7)
			(day == 1) ? 7 : day - 1
		}
		static int hourOf(Date date) { date.getAt(Calendar.HOUR_OF_DAY) }
		static int minuteOf(Date date) { date.getAt(Calendar.MINUTE) }

		static <T> Collection<Collection<T>> collectWithHistory(Collection<T> collection, Closure shouldKeepElement, Closure callback) {
			def result = []
			def previousValues = new LinkedList()

			for (value in collection) {
				while (!previousValues.empty && !shouldKeepElement(previousValues.first(), value)) {
					previousValues = previousValues.tail()
				}

				result << callback(previousValues, value)

				if (shouldKeepElement(value, value)) previousValues << value
			}
			result
		}

		static <T> Collection<Collection<T>> pairs(Collection<T> collection) {
			Collection<Collection<T>> result = collection.inject([]) { acc, value ->
				if (!acc.empty) acc.last() << value
				acc + [[value]]
			}
			if (!result.empty) result.remove(result.size() - 1)
			result
		}
	}
}

