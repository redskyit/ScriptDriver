# seleniumscript

A simple scripting language for driving selenium based web automation.

### Overview

Selenium Script is a basic scripting language designed to take some of the pain out of writing and updating automation and regression tests for web pages.  Selenium is a powerful automation tool for web pages, but it requires a fair amount of code to do even the simplest things.  The idea behind Selenium Script is to hide all the complication inside the script engine, and expose the power of selenium through the simplest of scripting commands.  Simple and quick was the goal behind this project.

### Project Status

The current state of this project is, that I developed it just enough for me to be able to provide a reliable set of regression tests for the html5 application I was developing.  It was also my first time ever using selenium.  That means that this is by no means a complete scripting language for selenium, it certainly does not support all of the features selenium supports, and only supports ChromeDriver at present.  It also means that it was developed incrememntally and new features were added quickly as I found a need for them, which means there was no original design, and no design approval process for enhancements.

So what I have ended up with is something that works for automating web testing in chrome (for the most part) but is lacking in some areas and could do with a re-think in others.

If you're looking for something quite simple yet quite powerful to get you going in web automation / regression testing, you should give Selenium Script a go, but you might have to get your hands dirty in the engine code to make it do exactly what you want.

### Future Plans

As far as what our plans are for this project, it is to continue to extend the current language as needed, add support for browsers other than chrome, possibly re-factor some of the syntax and maybe even re-implement the language in something other than java (nodejs perhaps).  The test script files themselves are platform independent.  The engine is written in java so should run cross platform, though I have not yet attempted to run it on anything other than OSX.

### Open Source

We have decided to release this project as open source so that others can maybe benefit from it, and maybe improve on what we have done so far.

## Examples

    browser start
    browser get "https://github.com/"
    wait 30
    select "#js-command-bar-field" send "Selenium Script"
    select ".choice:first-child" click
    sleep 30
