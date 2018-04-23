# ScriptDriver

A simple scripting language for driving selenium based web automation and 
regression testing.

## Overview

Script Driver is a simple yet powerful scripting language for Selenium, 
designed to take some of the pain out of writing and updating automation and 
regression tests for web pages.  Selenium is a powerful automation tool for 
web pages, but it requires a fair amount of code to do even the simplest 
things.  The idea behind Script Driver is to hide all the complication inside the script engine, and expose the power of selenium through the simplest of scripting commands.  Simple and quick was the goal behind this project.

## Project Status

The current state of this project is, that I developed it enough for me to be 
able to provide a reliable set of regression tests for the html5 application I 
am developing.  It is by no means a complete scripting language for selenium, 
as it does not support all of the features selenium supports, and only 
supports ChromeDriver at present.  It was also developed incrememntally and 
new features were added quickly and so there was no original design, and no 
design approval process for enhancements.

ScriptDriver continues to be the main testing environment for the HTML5 
application we maintain and has been ever since it was first released.

If you're looking for something simple, yet quite powerful, to get you going 
in web automation / regression testing, you should give ScriptDriver a go.

## Release Notes

Version `0.5.1` is now available.
- `Enh`: Fix issue with enabled, selected and displayed checks not working properly.

Version `0.5.0` is now available.
- `Enh`: Upgrade to selenium server 3.11.0 and chromedriver to 2.37

Version `0.4.0` is now available.
- `Enh`: Upgrade to selenium server 3.3.1

Version `0.3.3` is now available.
- `Fix`: Issue #19: Change `info` output to output `displayed` check before `at` and `size` checks.

Version `0.3.2` is now available.
- `Enh`: Add browser control verbs `refresh`, `back` and `forward`
- `Fix`: CRC `checksum` of non-input elements (like div).

Version `0.3.1` includes a couple of fixes and a special version of the ```click``` command.

- `Fix`: Don't try and wait for -tve periods
- `Fix`: browser wait <seconds> can now be a alias argument
- `Enh`: Added click-now command, performs a click without waiting.  Useful
in some circumstances when elements are quickly changing.

Version `0.3` includes screen shot support, and changes the way elements and 
click timeouts work, now using webdriver built in functionality.

- `Enh`: screen shot support
- `Enh`: use WebDriverWait for clicks
- `Enh`: browser wait <seconds> set default driver wait (for finding elements)
- `Enh`: wait clickable (wait for something to be clickable)

Version `0.2` contains some enhancements and new features and bug fixes as well 
as some refactoring that went on under the hood:

- `Enh`: Improved wait support including an auto wait feature
- `New`: push/pop wait
- `New`: Alias parameters (aka functions)
- `Fix`: sleep fractions (sleep 0.1)

Previous updates to `0.1` included:

- `New`: browser chrome
- `New`: Checksum support
- `New`: log auto on
- `Enh`: abort test on javascript errors
- `New`: scroll-into-view
- `Enh`: Wildcard support for at <x> and size <width>

## Future Plans

As far as to what our plans are for this project, it is 

- to continue to extend the current language as needed (ongoing)
- add support for browsers other than chrome
- possibly re-factor some of the syntax (extending it on an ongoing basis)
- maybe even re-implement the language in something other than java (nodejs perhaps).  

The test script files themselves are platform independent.  The engine is written in java so should run cross platform, though I have only tested it on OS X and Windows.

## Open Source

We have decided to release this project as open source so that others can maybe benefit from it, and maybe improve on what we have done so far.

## Examples

    browser start
    browser get "https://github.com/"
    wait 30
    select "#js-command-bar-field" send "ScriptDriver"
    select ".choice:first-child" click
    sleep 30

## Getting Started

See Engine/lib/INSTALL.txt for instructions on how to download selenium and chromedriver.

To build this project requires eclipse IDE.  Proceed as follows.

1. Open this the Engine folder as a Workspace.
2. Go to work bench
3. File, Import ... and choose General -> Existing projects into workspace
4. Specify the Engine folder as the root.  Eclipse will find TestEngine project.
5. Click finish to add.
6. Right click build.xml and select Run As -> Ant Build

Sorry the instructions are a bit sparse, not yet fully figured out what exactly from eclipse needs to go in source control for it to remember all the project settings.

## Binary Distribution(s)

A pre-built version of the current release is available here:

### Version `0.5.1`
[Download](https://www.dropbox.com/s/gujil6stzds7705/ScriptDriver-0.5.1.tgz?dl=1)

### Previous Versions
[`0.5.0`](https://www.dropbox.com/s/ikcnau8tto8mt0g/ScriptDriver-0.5.0.tgz?dl=1)
[`0.4.0`](https://www.dropbox.com/s/pfag8d6xpzrj2rb/ScriptDriver-0.4.0.tgz?dl=1)
[`0.3.3`](https://www.dropbox.com/s/a9ee8ngdrk8s7p4/ScriptDriver-0.3.3.tgz?dl=1) | 
[`0.3.2`](https://www.dropbox.com/s/2xmoou4f7lui7ek/ScriptDriver-0.3.2.tgz?dl=1) | 
[`0.3.1`](https://www.dropbox.com/s/eae5i0a17sjy2el/ScriptDriver-0.3.1.tgz?dl=1) | 
[`0.3`](https://www.dropbox.com/s/ni7tv32bckvynge/ScriptDriver-0.3.tgz?dl=1) | 
[`0.2`](https://www.dropbox.com/s/pkmxf78hpjwwn5e/ScriptDriver-0.2.tgz?dl=1) | 
[`0.1`](https://www.dropbox.com/s/ludzkdmkm4du59o/ScriptDriver-0.1.tgz?dl=1)

### All Versions
[Browse](https://www.dropbox.com/sh/224wzmmyc5e5wb5/AAAHWZbLjgIb2adJD2E2ey6ja?dl=0) | 
[Download](https://www.dropbox.com/sh/224wzmmyc5e5wb5/AAAHWZbLjgIb2adJD2E2ey6ja?dl=1)

The archive is for Windows and OSX.  Linux users will need to download the correct version of chromedriver.

Extract the archive to a folder.  Navigate to the folder at a command prompt (windows) or shell (OSX/Linux).

For windows, edit run.cmd and alter the JAVA variable to point at your java install.

Run the sample script provided as follows:-

OSX/Linux: 

    ./run.sh examples/github-search.test

Windows:   

    run.cmd examples/github-search.test

## Documentation
### Language Syntax

https://github.com/redskyit/ScriptDriver/wiki/Language-Syntax-(v0.3)
https://github.com/redskyit/ScriptDriver/wiki/Language-Syntax-(v0.2)
