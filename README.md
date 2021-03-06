### What is this?

This is a plugin for [IntelliJ](https://github.com/JetBrains/intellij-community)-based IDEs to grab
and analyze project source code history.

It has has two parts:
 - reading project history from version control and saving it as csv file
 - reading history from csv, analyzing and visualizing it with [d3.js](http://d3js.org/) (needs a browser with SVG support)

Warnings:
 - all VCS supported by IntelliJ should work but I only tried Git and Svn
 - all browsers with SVG support should work but I only tried Chrome and Safari


### Why?
There seems to be a lot of interesting data captured in version control systems, yet we don't use it that much.
This is an attempt to make looking at project history easier.

 - converting history to csv is useful because it's easy to read and process in any language (or even in a spreadsheet)
 - interactive visualization is cool because it's fun to play with and can hopefully give deeper insight into project history


### Screenshots / Examples
Screenshot of grab history dialog:
<img src="https://raw.github.com/dkandalov/code-history-mining/master/grab-history-screenshot.png" alt="screenshot" title="screenshot" align="center"/>

For visualization examples please see [GitHub pages](http://dkandalov.github.com/code-history-mining/junit.html).
(It's a separate page to keep readme small and not to put SVG into markdown.)


### How to use
Not released yet. Will be soon in IntelliJ plugin repository.


### Output csv format
TODO


### Acknowledgments
 - inspired by Michael Feathers workshop and [Delta Flora](https://github.com/michaelfeathers/delta-flora) project
 - all visualizations are based on awesome [d3.js examples](https://github.com/mbostock/d3/wiki/Gallery)
