// Copyright 2014, RedSky IT
// This software is release under the MIT License.
// See LICENSE file for details.
//
package com.redskyit.scriptDriver;

import java.io.BufferedReader;
import java.io.CharArrayReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StreamTokenizer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.zip.CRC32;

import org.openqa.selenium.By;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.InvalidElementStateException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Point;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.browserlaunchers.Sleeper;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.logging.Logs;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.RemoteWebElement;

public class RunTests {
	enum ContextType {
		None, Field, Select, Script, XPath
	}
	ChromeOptions options = null;
	Map<String, Object> prefs = null;
	ChromeDriver driver = null;
	RemoteWebElement context = null;
	String selector = null;
	Actions actions = null;
	String contextSelector = null;
	ContextType ctype = ContextType.None;
	private long _waitFor = 0;
	private boolean _if;
	private boolean _test;
	private boolean _skip;
	private boolean _not;
	HashMap<String, String> aliases = new HashMap<String,String>();
	private boolean autolog = false;
	
    public RunTests() throws IOException {
	}

	public static void main(String[] args) throws IOException {
    	RunTests app = new RunTests();
		int exitstatus = app.run(args);
    	System.exit(exitstatus);
	}
	
	public int run(String[] args) {
		File script = null;
		String onexit = "--onsuccess";
		int exitstatus = 0;
    	try {
    		for (int i = 0; i < args.length; i++) {
				script = runScript(args[i]); 
			}
    	} catch (Exception e) {
    		e.printStackTrace();
    		onexit = "--onfail";
    		exitstatus = 1;
    	}
    	
    	// If we have an onexit handler to call, then run it now
    	if (null != onexit) {
    		try {
				runAlias(onexit, script);
			} catch (Exception e) {
				e.printStackTrace();
				exitstatus = 2;
			}
    	}
    	
    	// Cleanup
    	if (null != driver) {
    		driver.quit();
    	}
    	
    	return exitstatus;
	}
	
	private void initTokenizer(StreamTokenizer tokenizer) {
		tokenizer.quoteChar('"');
		tokenizer.slashStarComments(true);
		tokenizer.slashSlashComments(true);
		tokenizer.whitespaceChars(' ', ' ');
		tokenizer.whitespaceChars(0x09,0x09);
		tokenizer.wordChars('$','$');		// treat $ as part of word
		tokenizer.wordChars('#','#');		// treat # as part of word
		tokenizer.wordChars('_','_');		// treat $# as part of word
	}
	
	private StreamTokenizer openScript(File file) throws IOException {
		BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF8"));
		StreamTokenizer tokenizer = new StreamTokenizer(in);
		initTokenizer(tokenizer);
		return tokenizer;		
	}
	
	private StreamTokenizer openString(String code) {
		// StreamTokenizer tokenizer = new StreamTokenizer(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(code.getBytes()))));
		StreamTokenizer tokenizer = new StreamTokenizer(new CharArrayReader(code.toCharArray()));
		initTokenizer(tokenizer);
		return tokenizer;		
	}

	private File runScript(String filename) throws Exception {
		File file = new File(filename);
		StreamTokenizer tokenizer = openScript(file);
		while (tokenizer.nextToken() != StreamTokenizer.TT_EOF) {
			if (tokenizer.ttype == StreamTokenizer.TT_WORD) {
				runCommand(tokenizer, file, file.getName());
			}
		}
		return file;
	}
	
	private boolean runAlias(String alias, File file) throws Exception {
		String code = aliases.get(alias);
		if (null != code) {
			StreamTokenizer tokenizer = openString(code);
			while (tokenizer.nextToken() != StreamTokenizer.TT_EOF) {
				if (tokenizer.ttype == StreamTokenizer.TT_WORD) {
					runCommand(tokenizer, file, alias);
				}
			}		
			return true;
		}
		return false;
	}
	
	private boolean runString(String script, File file, String cmd) throws Exception {
		StreamTokenizer tokenizer = openString(script);
		while (tokenizer.nextToken() != StreamTokenizer.TT_EOF) {
			if (tokenizer.ttype == StreamTokenizer.TT_WORD) {
				runCommand(tokenizer, file, cmd);
			}
		}
		return true;		
	}
	
	private interface ArgHandler {
		void processArg(StreamTokenizer tokenizer, String arg) throws IOException;
	}

	private void parseArgs(StreamTokenizer tokenizer, ArgHandler handler) throws Exception {
		tokenizer.nextToken();
		if (tokenizer.ttype == '{') {
			int braceLevel = 1;
			System.out.print(" {");
			tokenizer.eolIsSignificant(true);
			tokenizer.nextToken();
			while (tokenizer.ttype == StreamTokenizer.TT_WORD 
					|| tokenizer.ttype == '"' 
					|| tokenizer.ttype == StreamTokenizer.TT_NUMBER 
					|| tokenizer.ttype == '\n'
					|| tokenizer.ttype == '{'
					|| tokenizer.ttype == '}'
					|| tokenizer.ttype == ','
					|| tokenizer.ttype == '*'
					|| tokenizer.ttype == ':'
			) {
				System.out.print(' ');
				String arg;
				switch(tokenizer.ttype) {
				case '{':
					braceLevel ++;
					arg = "{";
					break;
				case '}': 
					if (--braceLevel == 0) {
						System.out.println("}");
						tokenizer.eolIsSignificant(false);
						return;
					}
					arg = "}";
					break;
				case StreamTokenizer.TT_NUMBER: 
					arg = String.valueOf(tokenizer.nval); 
					break;
				case StreamTokenizer.TT_EOL:    
					arg = "\n"; 
					break;
				case '"':
					// 5 backslashed required in replace string because its processed once
					// as a string (yielding \\") and then again by the replace method
					// of the regular expression (so \\" becomes \")
					arg = '"' + tokenizer.sval.replaceAll("\"", "\\\\\"") + '"';
					break;
				case ',': arg = ","; break;
				case ':': arg = ":"; break;
				case '*': arg = "*"; break;
				default:
					arg = tokenizer.sval;
				}
				System.out.print(arg);
				handler.processArg(tokenizer, arg);
				tokenizer.nextToken();
			}
			System.out.println();			
			throw new Exception("args unexpectd token " + tokenizer.ttype);
		}
		System.out.println();
		tokenizer.pushBack();		// no arguments
	}
	
	private class ArgString implements ArgHandler {
		char sep = ' ';
		char isep = sep;
		boolean quoteWords = false;
		String args = null;
		public ArgString(char sep, boolean quoteWords) {
			this.isep = this.sep = sep;
			this.quoteWords = quoteWords;
		}
		public void processArg(StreamTokenizer tokenizer, String arg) {
			if (quoteWords && tokenizer.ttype == StreamTokenizer.TT_WORD) {
				arg = '"' + arg + '"';
			}
			if (tokenizer.ttype == ',') {
				sep = ',';	// next word joined by comma
				return;
			}
			if (tokenizer.ttype == ':') {
				sep = ':';
				return;
			}
			if (tokenizer.ttype == '*') {
				arg = "*";
			}
			args = args == null ? arg : args + sep + arg;
			sep = isep;
		}
		String get() {
			return args;
		}
	};

	private class ArgArray implements ArgHandler {
		List<String> args = new ArrayList<String>();
		public void processArg(StreamTokenizer tokenizer, String arg) {
			if (tokenizer.ttype == '"' || tokenizer.ttype == '\'') {
				arg = arg.substring(1,arg.length()-1);
			}
			args.add(arg);
		}
		List<String> get() {
			return args;
		}
	};

	private String getArgs(StreamTokenizer tokenizer, char sep, boolean quoteWords) throws Exception {
		ArgString args = new ArgString(sep, quoteWords);
		parseArgs(tokenizer, args);
		return args.get();
	}

	private List<String> getArgs(StreamTokenizer tokenizer) throws Exception {
		ArgArray args = new ArgArray();
		parseArgs(tokenizer, args);
		return args.get();
	}

	private void runCommand(StreamTokenizer tokenizer, File file, String source) throws Exception {
		// Automatic log dumping
		if (autolog && null != driver) {
			dumpLog();
		}
		
		String cmd = tokenizer.sval;
		System.out.printf("[%s,%d] ", source, tokenizer.lineno());
		System.out.print(tokenizer.sval);

		if (cmd.equals("browser")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				System.out.print(' ');
				System.out.print(tokenizer.sval);
				
				if (tokenizer.sval.equals("prefs")) {
					tokenizer.nextToken();
					if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
						System.out.print(' ');
						System.out.print(tokenizer.sval);
						String pref = tokenizer.sval;
						tokenizer.nextToken();
						System.out.print(' ');
						if (_skip) return;
						if (null == options) options = new ChromeOptions();
						if (null == prefs) prefs = new HashMap<String, Object>();
						switch(tokenizer.ttype) {
						case StreamTokenizer.TT_WORD:
						case '"':
							System.out.println(tokenizer.sval);
							prefs.put(pref, tokenizer.sval);
							return;
						case StreamTokenizer.TT_NUMBER:
							System.out.println(tokenizer.nval);
							prefs.put(pref, tokenizer.nval);
							return;
						}
					}
					System.out.println();
					throw new Exception("browser option command argument missing");
				}
				
				if (tokenizer.sval.equals("option")) {
					tokenizer.nextToken();
					if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {		// expect a quoted string
						System.out.print(' ');
						System.out.println(tokenizer.sval);
						if (_skip) return;
						if (null == options) options = new ChromeOptions();
						options.addArguments(tokenizer.sval);
						return;
					}
					System.out.println();
					throw new Exception("browser option command argument missing");
				}
				
				if (tokenizer.sval.equals("start")) {
					System.out.println();
					if (null == driver) {
						// https://sites.google.com/a/chromium.org/chromedriver/capabilities
						DesiredCapabilities capabilities = DesiredCapabilities.chrome();
						LoggingPreferences logs = new LoggingPreferences();
						if (null != options) {
							if (null != prefs) options.setExperimentalOption("prefs", prefs);
							capabilities.setCapability(ChromeOptions.CAPABILITY, options);
						}
						logs.enable(LogType.BROWSER, Level.ALL);
						capabilities.setCapability(CapabilityType.LOGGING_PREFS, logs);
    					driver = new ChromeDriver(capabilities);
	    				driver.setLogLevel(Level.ALL);
	    				actions = new Actions(driver);			// for advanced actions
	    			}
	    			return;
				}

				if (null == driver) {
					System.out.println();
					throw new Exception("browser start must be used before attempt to interract with the browser");
				}
				
				if (tokenizer.sval.equals("get")) {
					tokenizer.nextToken();
					if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {		// expect a quoted string
						System.out.print(' ');
						System.out.println(tokenizer.sval);
						if (_skip) return;
		    			if (null == driver) driver = new ChromeDriver(options);
						driver.get(tokenizer.sval);
						return;
					}
					System.out.println();
					throw new Exception("browser get command argument should be a quoted url");
				}
				
				if (tokenizer.sval.equals("close")) {
					System.out.println();
					if (!_skip) {
						driver.close();
						autolog = false;
					}
					return;
				}
				
				if (tokenizer.sval.equals("size")) {
					int w = 0, h = 0;
					tokenizer.nextToken();
					if (tokenizer.ttype == StreamTokenizer.TT_NUMBER) {
						w = (int) tokenizer.nval;
						System.out.print(' ');
						System.out.print(w);
						tokenizer.nextToken();
						if (tokenizer.ttype == ',') {
							tokenizer.nextToken();
							System.out.print(',');
							if (tokenizer.ttype == StreamTokenizer.TT_NUMBER) {
								h = (int) tokenizer.nval;
								System.out.print(h);
								System.out.println();
								if (!_skip) driver.manage().window().setSize(new Dimension(w,h));
								return;
							}
						}
					}
					throw new Exception("browser size arguments error at line " + tokenizer.lineno());
				}
				if (tokenizer.sval.equals("pos")) {
					int x = 0, y = 0;
					tokenizer.nextToken();
					if (tokenizer.ttype == StreamTokenizer.TT_NUMBER) {
						x = (int) tokenizer.nval;
						System.out.print(' ');
						System.out.print(x);
						tokenizer.nextToken();
						if (tokenizer.ttype == ',') {
							tokenizer.nextToken();
							System.out.print(',');
							if (tokenizer.ttype == StreamTokenizer.TT_NUMBER) {
								y = (int) tokenizer.nval;
								System.out.print(y);
								System.out.println();
								if (!_skip) driver.manage().window().setPosition(new Point(x,y));
								return;
							}
						}
					}
					throw new Exception("browser size arguments error at line " + tokenizer.lineno());
				}
				throw new Exception("browser unknown command argument at line " + tokenizer.lineno());
			}
			throw new Exception("browser missing command argument at line " + tokenizer.lineno());
		}
		
		if (cmd.equals("alias")) {
			String alias = null, args = null;
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				System.out.print(' ');
				System.out.print(tokenizer.sval);
				alias = tokenizer.sval;
				args = getArgs(tokenizer, ' ', false);
				if (_skip) return;
				addAlias(alias, args);		// add alias
				return;
			}
			System.out.println();			
			throw new Exception("alias name expected");
		}

		if (cmd.equals("while")) {
			String args = null;
			args = getArgs(tokenizer, ' ', false);
			if (_skip) return;
			boolean exitloop = false;
			while (!exitloop) {
				try {
					runString(args, file, "while");
				} catch(Exception e) {
					exitloop = true;
				}
			}
			return;
		}

		if (cmd.equals("include")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				if (_skip) return;
				System.out.print(' ');
				System.out.println(tokenizer.sval);
				File include = new File(
						tokenizer.sval.startsWith("/") 
						? tokenizer.sval 
						: file.getParentFile().getCanonicalPath() + "/" + tokenizer.sval
					);
				runScript(include.getCanonicalPath());
				return;
			}
			throw new Exception("include argument should be a quoted filename");
		}
		
		if (cmd.equals("exec")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				String command = tokenizer.sval;
				System.out.print(' ');
				System.out.print(command);
				List<String> args = getArgs(tokenizer);
				File include = new File(command.startsWith("/") 
										? command 
										: file.getParentFile().getCanonicalPath() + "/" + command
									);
				command = include.getCanonicalPath();
				System.out.println(command);
				List<String> arguments = new ArrayList<String>();
				arguments.add(command);
				arguments.addAll(args);
				Process process = Runtime.getRuntime().exec(arguments.toArray(new String[arguments.size()]));
				BufferedReader reader = new BufferedReader( new InputStreamReader( process.getInputStream() ) );
				String line = "";
				while ((line = reader.readLine()) != null) {
					System.out.println(line);
				}
				int exitStatus = process.waitFor();
				if (exitStatus != 0) {
					throw new Exception("exec command returned failure status " + exitStatus);
				}
				return;
			}
			System.out.println();
			throw new Exception("exec argument should be string or a word");
		}

		if (cmd.equals("exec-include")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				String command = tokenizer.sval;
				System.out.print(' ');
				System.out.print(command);
				List<String> args = getArgs(tokenizer);
				File include = new File(command.startsWith("/") 
										? command 
										: file.getParentFile().getCanonicalPath() + "/" + command
									);
				command = include.getCanonicalPath();
				System.out.println(command);
				List<String> arguments = new ArrayList<String>();
				arguments.add(command);
				arguments.addAll(args);
				Process process = Runtime.getRuntime().exec(arguments.toArray(new String[arguments.size()]));
				BufferedReader reader = new BufferedReader( new InputStreamReader( process.getInputStream() ) );
				String s = "", line = "";
				while ((line = reader.readLine()) != null) {
					s += line + "\n";
				}
				int exitStatus = process.waitFor();
				if (exitStatus != 0) {
					throw new Exception("exec-include command returned failure status " + exitStatus);
				}
				if (s.length() > 0) {
					runString(s,file,tokenizer.sval);
				}
				return;
			}
			System.out.println();
			throw new Exception(cmd + " argument should be string or a word");
		}
		
		if (cmd.equals("log")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				String action = tokenizer.sval;
				System.out.print(' ');
				System.out.print(action);
				if (action.equals("dump")) {
					System.out.println("");
					if (driver != null) dumpLog();
					return;
				}
				if (action.equals("auto")) {
					tokenizer.nextToken();
					String onoff = tokenizer.sval;
					System.out.print(' ');
					System.out.println(onoff);
					autolog = onoff.equals("on") || onoff.equals("true");
					return;
				}
				throw new Exception("invalid log action");
			}
			System.out.println();
			throw new Exception("log argument should be string or a word");
		}
				
		if (null == driver) {
			throw new Exception("browser start must be used before attempt to interract with the browser");
		}

		if (cmd.equals("field") || cmd.equals("id") || cmd.equals("test-id")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				if (_skip) return;
				System.out.print(' ');
				System.out.println(tokenizer.sval);
				this.setContextToField(tokenizer);
				return;
			}
			System.out.println();
			throw new Exception(cmd + " command requires a form.field argument");
		}

		if (cmd.equals("select")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				if (_skip) return;
				System.out.print(' ');
				System.out.println(tokenizer.sval);
				this.selectContext(tokenizer);
				return;
			}
			System.out.println();
			throw new Exception(cmd + " command requires a css selector argument");
		}
		
		if (cmd.equals("xpath")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				if (_skip) return;
				System.out.print(' ');
				System.out.println(tokenizer.sval);
				this.xpathContext(tokenizer);
				return;
			}
			System.out.println();
			throw new Exception(cmd + " command requires a css selector argument");
		}
		
		if (cmd.equals("wait")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_NUMBER) {
				System.out.print(' ');
				System.out.println(tokenizer.nval);
				
				// we will repeat then next select type command until it succeeds or we timeout
				_waitFor = (long) ((new Date()).getTime() + (tokenizer.nval * 1000));
				return;
			}
			throw new Exception(cmd + " command requires a seconds argument");
		}
		
		if (cmd.equals("if")) {
			_if = true;
			System.out.println();
			return;
		}
		
		if (cmd.equals("then")) {
			_if = false;
			_skip = !_test;
			System.out.println();
			return;
		}

		if (cmd.equals("else")) {
			_if = false;
			_skip = _test;
			System.out.println();
			return;
		}

		if (cmd.equals("endif")) {
			_skip = false;
			System.out.println();
			return;
		}
		
		if (cmd.equals("not")) {
			System.out.println();
			_not = true;
			return;
		}

		if (cmd.equals("set") || cmd.equals("send")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				if (_skip) return;
				System.out.print(' ');
				System.out.println(tokenizer.sval);
				this.setContextValue(tokenizer, cmd.equals("set"));
				return;
			}
			System.out.println();
			throw new Exception("set command requires a value argument");
		}

		if (cmd.equals("test") || cmd.equals("check")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"' || tokenizer.ttype == '\'') {
				if (_skip) return;
				System.out.print(' ');
				System.out.println(tokenizer.sval);
				this.testContextValue(tokenizer, false);
				return;
			}
			System.out.println();
			throw new Exception(cmd + " command requires a value argument");
		}
		
		if (cmd.equals("checksum")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"' || tokenizer.ttype == '\'') {
				if (_skip) return;
				System.out.print(' ');
				System.out.println(tokenizer.sval);
				this.testContextValue(tokenizer, true);
				return;
			}
			System.out.println();
			throw new Exception(cmd + " command requires a value argument");
		}

		if (cmd.equals("click")) {
			System.out.println();
			if (null == context) throw new Exception(cmd + " command requires a field context at line " + tokenizer.lineno());
			do {
				try {
					if (!_skip) {
						context.click();
					}
					return;
				} catch(StaleElementReferenceException e) {
					// element has gone stale, re-select it
					System.out.println("// EXCEPTION : StaleElementReference");
				} catch(WebDriverException e2) {
					System.out.println("// EXCEPTION : WebDriverException");	
					// Try and auto-recover by scrolling this element into view
					scrollContextIntoView(context);
				}
				sleepAndReselect(100);
			} while (_waitFor > 0 && (new Date()).getTime() < _waitFor);
			info(context, contextSelector, false);
			throw new Exception("click failed at line " + tokenizer.lineno());
		}
		
		if (cmd.equals("scroll-into-view")) {
			System.out.println();
			if (null == context) throw new Exception(cmd + " command requires a field context at line " + tokenizer.lineno());
			if (!_skip) {
				try {
					scrollContextIntoView(context);
				} catch(Exception e) {
					System.out.println(e.getMessage());
					info(context, contextSelector, false);
					throw e;
				}
			}
			return;			
		}

		if (cmd.equals("clear")) {
			System.out.println();
			if (null == context) throw new Exception("clear command requires a field context at line " + tokenizer.lineno());
			if (!_skip) context.clear();
			return;
		}
		
		if (cmd.equals("call")) {
			String function = null, args = null;
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {		// expect a quoted string
				function = tokenizer.sval;
				System.out.print(' ');
				System.out.print(function);
				args = getArgs(tokenizer, ',', true);
				if (_skip) return;
				if (null == args) args = "";
				String script = "var result = window.RegressionTest.test('"+function+"',[" + args + "]);"
								+ "arguments[arguments.length-1](result);";
				System.out.println(script);
				Object result = driver.executeAsyncScript(script);
				if (null != result) {
					if (result.getClass() == RemoteWebElement.class) {
						context = (RemoteWebElement) result;
						ctype = ContextType.Script;
						selector = script;
						System.out.println("new context " + context);
					}
				}
				return;
			}
			System.out.println();
			throw new Exception("missing arguments for call statement at line " + tokenizer.lineno());
		}
		
		if (cmd.equals("enabled")) {
			System.out.println();
			if (null == context) throw new Exception("enabled command requires a context at line " + tokenizer.lineno());
			if (!_skip || context.isEnabled() != _not) {
				_not = false;
				return;
			}
			info(context, contextSelector, false);
			throw new Exception("enablede  check failed " + tokenizer.lineno());
		}

		if (cmd.equals("selected")) {
			System.out.println();
			if (null == context) throw new Exception("selected command requires a context at line " + tokenizer.lineno());
			do {
				try {
					if (_skip || context.isSelected() != _not) {
						_not = false;
						return;
					}
				} catch(StaleElementReferenceException e) {
					// element has gone stale, re-select it
					System.out.println("// EXCEPTION : StaleElementReference");
				}
				sleepAndReselect(100);
			} while (_waitFor > 0 && (new Date()).getTime() < _waitFor);
			info(context, contextSelector, false);
			throw new Exception("selected check failed " + tokenizer.lineno());
		}
		
		if (cmd.equals("displayed")) {
			System.out.println();
			if (null == context) throw new Exception("displayed command requires a context at line " + tokenizer.lineno());
			do {
				try {
					if (_skip || context.isDisplayed() != _not) {
						_not = false;
						return;
					}
				} catch(StaleElementReferenceException e) {
					// element has gone stale, re-select it
					System.out.println("// EXCEPTION : StaleElementReference");
				}
				sleepAndReselect(100);
			} while (_waitFor > 0 && (new Date()).getTime() < _waitFor);
			info(context, contextSelector, false);
			throw new Exception("displayed check failed " + tokenizer.lineno());
		}
		
		if (cmd.equals("at")) {
			int x = 0, y = 0;
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_NUMBER || tokenizer.ttype == '*') {
				x = (int) tokenizer.nval;
				System.out.print(' ');
				if (tokenizer.ttype == '*') {
					x = -1;
					System.out.print('*');
				} else {
					x = (int) tokenizer.nval;
					System.out.print(x);
				}
				tokenizer.nextToken();
				if (tokenizer.ttype == ',') {
					tokenizer.nextToken();
					System.out.print(',');
					if (tokenizer.ttype == StreamTokenizer.TT_NUMBER) {
						y = (int) tokenizer.nval;
						System.out.print(y);
						System.out.println();
						if (null == context) throw new Exception("at command requires a context at line " + tokenizer.lineno());
						do {
							try {
								Point loc = context.getLocation();
								if (_skip || ((loc.x == x || x == -1) && loc.y == y) != _not) {
									_not = false;
									return;
								}
							} catch(StaleElementReferenceException e) {
								// element has gone stale, re-select it
								System.out.println("// EXCEPTION : StaleElementReference");
							}
							sleepAndReselect(100);
						} while (_waitFor > 0 && (new Date()).getTime() < _waitFor);
						info(context, contextSelector, false);
						throw new Exception("Location check failed at line " + tokenizer.lineno());
					}
				}
			}
			System.out.println();
			throw new Exception("at missing co-ordiantes at line " + tokenizer.lineno());
		}

		if (cmd.equals("size")) {
			int mw = 0, w = 0, h = 0;
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_NUMBER || tokenizer.ttype == '*') {
				System.out.print(' ');
				if (tokenizer.ttype == '*') {
					mw = w = -1;
					System.out.print('*');
				} else {
					mw = w = (int) tokenizer.nval;
					System.out.print(w);
				}
				tokenizer.nextToken();
				if (tokenizer.ttype == ':') {
					tokenizer.nextToken();
					w = (int) tokenizer.nval;
					System.out.print(':');
					System.out.print(w);
					tokenizer.nextToken();
				}
				if (tokenizer.ttype == ',') {
					tokenizer.nextToken();
					System.out.print(',');
					if (tokenizer.ttype == StreamTokenizer.TT_NUMBER) {
						h = (int) tokenizer.nval;
						System.out.print(h);
						System.out.println();
						if (null == context) throw new Exception("size command requires a context at line " + tokenizer.lineno());
						do {
							try {
								Dimension size = context.getSize();
								if (_skip || ((mw == -1 || (size.width >= mw && size.width <= w)) && size.height == h) != _not) {
									_not = false;
									return;
								}
							} catch(StaleElementReferenceException e) {
								// element has gone stale, re-select it
								System.out.println("// EXCEPTION : StaleElementReference");
							}
							sleepAndReselect(100);
						} while (_waitFor > 0 && (new Date()).getTime() < _waitFor);
						info(context, contextSelector, false);
						throw new Exception("Size check failed at line " + tokenizer.lineno());
					}
				}
			}
			System.out.println();
			throw new Exception("size missing dimensions at line " + tokenizer.lineno());
		}

		if (cmd.equals("tag")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				System.out.print(' ');
				System.out.print(tokenizer.sval);
				System.out.println();
				String tag = "unknown";
				if (null == context) throw new Exception("tag command requires a context at line " + tokenizer.lineno());
				do {
					try {
						tag = context.getTagName();
						if (_skip || tokenizer.sval.equals(tag) != _not) {
							_not = false;
							return;
						}
					} catch(StaleElementReferenceException e) {
						// element has gone stale, re-select it
						System.out.println("// EXCEPTION : StaleElementReference");
					}
					sleepAndReselect(100);
				} while (_waitFor > 0 && (new Date()).getTime() < _waitFor);
				info(context, contextSelector, false);
				throw new Exception("tag \"" + tokenizer.sval + "\" check failed, tag is " + tag + " at line " + tokenizer.lineno());
			}
			System.out.println();
			throw new Exception("tag command has missing tag name at line " + tokenizer.lineno());
		}

		if (cmd.equals("info")) {
			System.out.println();
			if (null == context) throw new Exception("info command requires a context at line " + tokenizer.lineno());
			info(context, contextSelector, true);
			return;
		}
		
		if (cmd.equals("alert")) {
			System.out.println();
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				System.out.print(' ');
				System.out.print(tokenizer.sval);
				if (tokenizer.sval.equals("accept")) {
					System.out.println();
					if (!_skip) driver.switchTo().alert().accept();
					return;
				}
			}
			System.out.println();
			throw new Exception("alert syntax error at line " + tokenizer.lineno());
		}
		
		if (cmd.equals("dump")) {
			System.out.println();
			if (!_skip) dump();
			return;
		}
		
		if (cmd.equals("mouse")) {
			parseArgs(tokenizer, new ArgHandler() {
				public void processArg(StreamTokenizer tokenizer, String arg) {
					int l = arg.length();
					if (arg.equals("center")) {
						actions.moveToElement(context);
					} else if ((l > 1 && arg.substring(1,l-1).equals("0,0")) || arg.equals("origin")) {
						actions.moveToElement(context,0,0);
					} else if (arg.equals("body")) {
						actions.moveToElement(driver.findElement(By.tagName("body")),0,0);
					} else if (arg.equals("down")) {
						actions.clickAndHold();
					} else if (arg.equals("up")) {
						actions.release();
					} else if (arg.equals("click")) {
						actions.click();
					} else if (l > 1) {
						String [] a = arg.substring(1,l-1).split(",");
						actions.moveByOffset(Integer.valueOf(a[0]), Integer.valueOf(a[1]));
					} else {
						// no-op
					}
				}						
			});
			actions.release();
			actions.build().perform();
			return;
		}

		if (cmd.equals("sleep")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_NUMBER) {
				System.out.print(' ');
				System.out.println(tokenizer.nval);
				Sleeper.sleepTight((long) tokenizer.nval * 1000);
				return;
			}
			System.out.println();
			throw new Exception("sleep command argument should be a number");
		}
		
		if (cmd.equals("echo")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				String text = tokenizer.sval;
				System.out.print(' ');
				System.out.println(text);
				if (!_skip) System.out.println(text);
				return;
			}
			System.out.println();
			throw new Exception("echo argument should be string or a word");
		}

		if (cmd.equals("fail")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				String text = tokenizer.sval;
				System.out.print(' ');
				System.out.println(text);
				if (!_skip) {
					System.out.println("TEST FAIL: " + text);
					throw new Exception(text);
				}
				return;
			}
			System.out.println();
			throw new Exception("echo argument should be string or a word");
		}

		if (cmd.equals("debugger")) {
			System.out.println();
			Sleeper.sleepTightInSeconds(10);
			return;
		}
		
		if (aliases.containsKey(cmd)) {
			System.out.println();
			runAlias(cmd, file);
			return;
		}
		
		throw new Exception("unrecognised command, " + cmd);
	}

	private void dumpLog() throws Exception {
		Logs log = driver.manage().logs();
		LogEntries entries = log.get(LogType.BROWSER);
		// System.out.println(entries);
		List<LogEntry> list = entries.getAll();
		boolean fail = false;
		for (int i = 0; i < list.size(); i++) {
			LogEntry e = list.get(i);
			System.out.println(e);
			if (e.getLevel().getName().equals("SEVERE") 
					&& e.getMessage().indexOf("Uncaught ") != -1
					&& e.getMessage().indexOf(" Error:") != -1) {
				System.out.println("*** Uncaught Error ***");
				fail = true;
			}		    			
		}
		if (fail) throw new Exception("Unhandled Exception! Check console log for details");		
	}

	private void info(WebElement element, String selector, boolean verify) throws Exception {
		do {
			try {
				Point loc = element.getLocation();
				Dimension size = element.getSize();
				String tag = element.getTagName();
				System.out.print(null == selector ? "test-id \"" + element.getAttribute("test-id") + "\"" : selector);
				System.out.print(" info");
				System.out.print(" tag " + tag);
				System.out.print(" at " + loc.x + "," + loc.y);
				System.out.print(" size " + size.width + "," + size.height);
				System.out.print((element.isDisplayed() ? "" : " not") + " displayed");
				System.out.print((element.isEnabled() ? "" : " not") + " enabled");
				System.out.print((element.isSelected() ? "" : " not") + " selected");
				if (tag.equals("input") || tag.equals("select")) {
					System.out.print(" check \"" + element.getAttribute("value") + "\"");
				} else {
					if (tag.equals("textarea")) {
						CRC32 crc = new CRC32();
						crc.update(element.getAttribute("value").getBytes());
						System.out.print(" checksum \"crc32:" + crc.getValue() + "\"");
					} else {
						System.out.print(" check \"" + element.getText() + "\"");
					}
				}
				System.out.println();
				return;
			} catch(StaleElementReferenceException e) {
				// If element has gone stale during a dump, ignore it
				if (!verify) return;
				// element has gone stale, re-select it
				System.out.println("// EXCEPTION : StaleElementReference");
			} catch(Exception e) {
				if (verify) throw e;
				return;
			}
			sleepAndReselect(100);
		}  while (_waitFor > 0 && (new Date()).getTime() < _waitFor);
	}

	// Find all test-id fields, and dump their info
	private void dump() throws Exception {
		List<WebElement> elements = driver.findElements(By.xpath("//*[@test-id]"));
		for (int i = 0; i < elements.size(); i++) {
			info(elements.get(i),null,false);	
		}
	}

	private void addAlias(String alias, String args) {
		aliases.put(alias, args);		
	}
	
	private boolean compareStrings(String s1, String s2, boolean checksum) {
		if (checksum) {
			CRC32 crc = new CRC32();
			crc.update(s1.getBytes());
			return ("crc32:"+crc.getValue()).equals(s2);
		}
		return s1.equals(s2);
	}

	private void testContextValue(StreamTokenizer tokenizer, boolean checksum) throws Exception {
		String tagName = context.getTagName();
		if (tagName.equals("input") || tagName.equals("select") || tagName.equals("textarea")) { 
			System.out.println("// Checking element value is equal to '" + tokenizer.sval + "'");
			do {
				try {
					String value = context.getAttribute("value");
					if (_not != (null != value && compareStrings(value, tokenizer.sval, checksum))) {
						_not = false;
						return;
					}
					if (null == value) {
						System.out.println("// CHECK FAIL: EXPECTED '" + tokenizer.sval + "' BUT VALUE IS NULL");				
					} else {
						System.out.println("// CHECK FAIL: EXPECTED '" + tokenizer.sval + "' WHICH DOES NOT MATCH '" + value + "'");
					}
				} catch(StaleElementReferenceException e) {
					// element has gone stale, re-select it
					System.out.println("// EXCEPTION : StaleElementReference");
				}
				sleepAndReselect(100);
			} while (_waitFor > 0 && (new Date()).getTime() < _waitFor);
			info(context, contextSelector, false);
			throw new Exception("check value \"" + tokenizer.sval + "\" test failed for current field context at line " + tokenizer.lineno());
		} else {
			do {
				try {
					String value = context.getText();
					if (_not != (null != value && compareStrings(value, tokenizer.sval, checksum))) {
						_not = false;
						return;
					}
					if (null == value) {
						System.out.println("// CHECK FAIL: EXPECTED '" + tokenizer.sval + "' BUT VALUE IS NULL");				
					} else {
						System.out.println("// CHECK FAIL: EXPECTED '" + tokenizer.sval + "' WHICH DOES NOT MATCH '" + value + "'");
					}
				} catch(StaleElementReferenceException e) {
					// element has gone stale, re-select it
					System.out.println("// EXCEPTION : StaleElementReference");
				}
				sleepAndReselect(100);
			} while (_waitFor > 0 && (new Date()).getTime() < _waitFor);
			info(context, contextSelector, false);
			throw new Exception("check text " + tokenizer.sval + " test failed for current select context at line " + tokenizer.lineno());				
		}
	}

	private void setContextValue(StreamTokenizer tokenizer, boolean set) throws Exception {
		Exception e;
		String tagName = context.getTagName();
		if (tagName.equals("input") || tagName.equals("select") || tagName.equals("textarea")) {
			do {
				try {
					if (set && !tagName.equals("select")) context.clear();
					context.sendKeys(tokenizer.sval);
					return;
				} catch(StaleElementReferenceException se) {
					// element has gone stale, re-select it
					System.out.println("// EXCEPTION : StaleElementReference");
					e = se;
				} catch(InvalidElementStateException is) {
					System.out.println("// EXCEPTION : InvalidElementStateException : " + is.getMessage());
					e = is;					
				} catch(Exception ex) {
					System.out.println("// EXCEPTION : " + ex.getMessage());
					e = ex;
				}
				sleepAndReselect(100);
			} while (_waitFor > 0 && (new Date()).getTime() < _waitFor);
			throw new Exception("could not send keys to element " + e.getMessage());
		} else {
			throw new Exception("set cannot be used on a non-field context at line " + tokenizer.lineno());
		}
	}
	
	private void sleep(int ms) {
		Sleeper.sleepTight(ms);
	}
	
	private void sleepAndReselect(int ms) throws Exception {
		if (autolog) dumpLog();
		long waitTimer = (_waitFor - (new Date()).getTime());
		System.out.println("// SLEEP AND RESELECT [wait=" + waitTimer + "] ID " + context.getId());
		if (waitTimer < -(ms*2)) {
			System.out.println("AUTO WAIT FOR 1s");
			_waitFor = (new Date()).getTime() + 1000;
		}
		Sleeper.sleepTight(ms);
		try {
			if (ctype == ContextType.XPath || ctype == ContextType.Field) {
				context = (RemoteWebElement) driver.findElement(By.xpath(selector));
			}
			else if (ctype == ContextType.Select) {
				context = (RemoteWebElement) driver.findElement(By.cssSelector(selector));
			}
			else if (ctype == ContextType.Script) {
				Object result = driver.executeAsyncScript(selector);
				if (null != result) {
					if (result.getClass() == RemoteWebElement.class) {
						context = (RemoteWebElement) result;
					}
				}
			}
		} catch(NoSuchElementException e2) {
			// element has gone stale, re-select it
			System.out.println("// EXCEPTION : NoSuchElement");
		} catch (Exception e) {
			throw e;
		}
		System.out.println("// RESELECTED " + selector);
	}

	private void xpathContext(StreamTokenizer tokenizer) throws Exception {
		Exception e;
		ctype = ContextType.None;
		do {
			try {
				context = (RemoteWebElement) driver.findElement(By.xpath(tokenizer.sval));
				if (_not) { 
					_test = _not = false; 
					throw new Exception("not xpath " + tokenizer.sval + " is invalid on line " + tokenizer.lineno()); 
				}
				contextSelector = "xpath \"" + tokenizer.sval + "\"";
				ctype = ContextType.XPath;
				selector = tokenizer.sval;
				_test = true;
				return;
			} catch(Exception ex) {
				e = ex;
				sleep(100);
			}
		} while (_waitFor > 0 && (new Date()).getTime() < _waitFor);
		_waitFor = 0;
		_test = false;
		if (!_if)  {
			if (_not) { _test = true; context = null; _not = false; return; }
			throw new Exception("xpath " + tokenizer.sval + " is invalid on line " + tokenizer.lineno() + " " + e.getMessage());
		}
	}

	private void selectContext(StreamTokenizer tokenizer) throws Exception {
		Exception e;
		ctype = ContextType.None;
		selector = null;
		do {
			try {
				context = (RemoteWebElement) driver.findElement(By.cssSelector(tokenizer.sval));
				if (_not) { 
					_test = _not = false; 
					throw new Exception("not selector " + tokenizer.sval + " is invalid on line " + tokenizer.lineno()); 
				}
				contextSelector = "select \"" + tokenizer.sval + "\"";
				ctype = ContextType.Select;
				selector = tokenizer.sval;
				_test = true;
				return;
			} catch(Exception ex) {
				e = ex;
				sleep(100);
			}
		} while (_waitFor > 0 && (new Date()).getTime() < _waitFor);
		_waitFor = 0;
		_test = false;
		if (!_if) {
			if (_not) { _test = true; context = null; selector = null; _not = false; return; }
			throw new Exception("selector " + tokenizer.sval + " is invalid on line " + tokenizer.lineno() + " " + e.getMessage());
		}
	}

	private void setContextToField(StreamTokenizer tokenizer) throws Exception {
		Exception e;
		String query = "//*[@test-id='"+tokenizer.sval+"']";
		ctype = ContextType.None;
		selector = null;
		do {
			try {
				context = (RemoteWebElement) driver.findElement(By.xpath(query));
				if (_not) { 
					_test = _not = false; 
					throw new Exception("not test-id " + tokenizer.sval + " is invalid on line " + tokenizer.lineno()); 
				}
				contextSelector = "field \"" + tokenizer.sval + "\"";
				ctype = ContextType.Field;
				selector = query;
				_test = true;
				return;
			} catch(Exception ex) {
				e = ex;
				sleep(100);
			}
		} while (this._waitFor > 0 && (new Date()).getTime() < this._waitFor);
		this._waitFor = 0;
		_test = false;
		if (!_if) {
			if (_not) { _test = true; context = null; _not = false; return; }
			throw new Exception("field reference " + tokenizer.sval + " is invalid on line " + tokenizer.lineno() + " " + e.getMessage());
		}
	}
	
	private void scrollContextIntoView(WebElement element) throws Exception {
        Capabilities cp = ((RemoteWebDriver) driver).getCapabilities();
        if (cp.getBrowserName().equals("chrome")) {
            try {
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", element);
            } catch (Exception e) {
    			throw e;
            }
        }

	}
}
