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

	// Execution selection represents an executable chunk of source code, be that a script from file
	// or the body of a function/alias.  An execution selection has a body and optionally some arguments.
	private class ExecutionContext {
		private List<String> params = null;
		private String body = null;
		private List<Object> args = null;
		public ExecutionContext() {
		}
		public ExecutionContext(List<String> params, String body) {
			this.params = params;
			this.body = body;
			this.args = new ArrayList<Object>();
		}
		public String getBody() {
			return body;
		}
		public List<String> getParams() {
			return params;
		}
		public void addArg(Object arg) {
			if (null == args) throw new Error("Script: cannot add argument to alias without parameters"); 
			args.add(arg);
		}
		public Object getArg(String name) {
			if (args != null && args.size() > 0) {
				for (int i = 0; i < params.size(); i++) {
					if (params.get(i).equals(name)) {
						return args.get(i);
					}
				}
			}
			return null;
		}
		public String getExpandedString(StreamTokenizer tokenizer) {
			// Token is a word (rather than quoted string).  We only support $name format
			// variable substitution in words
			if (args != null && args.size() > 0) {
				if (tokenizer.ttype == StreamTokenizer.TT_WORD) {
					if (tokenizer.sval.startsWith("$")) {
						Object repl = this.getArg(tokenizer.sval.substring(1));
						if (null != repl) return repl.toString();
					}
					if (tokenizer.sval.startsWith("\\$")) {
						return tokenizer.sval.substring(1);
					}
				}
				if (tokenizer.ttype == '"') {
					// token is a quoted string
					String s = tokenizer.sval;
					for (int i = 0; i < params.size(); i++) {
						Object v = args.get(i);
						if (v.getClass() == Double.class) {
							Integer value = new Integer(((Double)v).intValue());
							s = s.replace("$I("+params.get(i)+")", value.toString());
						}
						s = s.replace("$("+params.get(i)+")",args.get(i).toString());
					}
					return s;
				}
			}
			return tokenizer.sval;
		}
		public double getExpandedNumber(StreamTokenizer tokenizer) {
			// Token is a word (rather than quoted string).  We only support $name format
			// variable substitution in words
			if (args != null && args.size() > 0) {
				if (tokenizer.ttype == StreamTokenizer.TT_NUMBER) {
					return tokenizer.nval;
				}
				if (tokenizer.ttype == StreamTokenizer.TT_WORD && tokenizer.sval.startsWith("$")) {
					Object repl = this.getArg(tokenizer.sval.substring(1));
					if (null != repl) {
						if (repl.getClass() == Double.class){
							return ((Double)repl).doubleValue();
						}
					}
				}
				throw new Error("Argument is not a number");
			}
			throw new Error("Arguments are missing from function call");
		}
		public void clearArgs() {
			if (args != null) args.clear();			
		}
	};

	enum SelectionType {
		None, Field, Select, Script, XPath
	};
	
	ChromeOptions options = null;
	Map<String, Object> prefs = null;
	ChromeDriver driver = null;
	Actions actions = null;
	RemoteWebElement selection = null;
	SelectionType stype = SelectionType.None;
	String selector = null;
	String selectionCommand = null;
	private long _waitFor = 0;
	private long _defaultWaitFor = 5000;
	private boolean _if;
	private boolean _test;
	private boolean _skip;
	private boolean _not;
	HashMap<String, ExecutionContext> functions = new HashMap<String,ExecutionContext>();
	private boolean autolog = false;
	private Dimension chrome = new Dimension(0,0);
	private HashMap<String, ArrayList<Object>> stacks = new HashMap<String, ArrayList<Object>>();

	private static String version = "0.2";
	
	@SuppressWarnings("serial")
	public class RetryException extends Exception {
		public RetryException(String message) {
			super(message);
		}
	};
	
	abstract class WaitFor {
		public WaitFor(String cmd, StreamTokenizer tokenizer, boolean requiresContext) throws Exception {
			if (requiresContext && null == selection) {
				throw new Exception(cmd + " command requires a field selection at line " + tokenizer.lineno());
			}
			int retry = 0;
			long now = 0;
			do {
				try {
					this.run();
					return;
				} catch(StaleElementReferenceException e) {
					// element has gone stale, re-select it
					System.out.println("// EXCEPTION : StaleElementReference : " + e.getMessage().split("\n")[0]);
					retry++;
				} catch(InvalidElementStateException is) {
					System.out.println("// EXCEPTION : InvalidElementStateException : " + is.getMessage().split("\n")[0]);
					scrollContextIntoView(selection);
					retry++;
				} catch(WebDriverException e2) {
					System.out.println("// EXCEPTION : WebDriverException : " + e2.getMessage().split("\n")[0]);	
					// Try and auto-recover by scrolling this element into view
					scrollContextIntoView(selection);
					retry++;
				} catch(RetryException r) {
					System.out.println("// EXCEPTION : RetryException : " + r.getMessage().split("\n")[0]);	
					// Try and auto-recover by scrolling this element into view
					retry++;
				} catch(Exception e3) {
					System.out.println("// EXCEPTION : " + e3.getMessage().split("\n")[0]);
					if (retry++ > 3) this.fail(e3);
				}
				// attempt to recover
				now = (new Date()).getTime();
				System.out.println(now + ": DEBUG: retry=" + retry + " calling sleepAndReselect(100) _waitFor = " + _waitFor);
				if (retry == 1 && now >= _waitFor) {
					System.out.println("// Wait timer already expired, apply default wait timer");
					System.out.println("// wait " + (_defaultWaitFor*1.0)/1000.0);
					_waitFor = (long) now + _defaultWaitFor;
				}
				sleepAndReselect(100);
			} while (_waitFor > 0 && now < _waitFor);

			// action failed
			info(selection, selectionCommand, false);
			_waitFor = 0;				// wait timer expired
			this.fail(new Exception(cmd + " failed at line " + tokenizer.lineno()));
		}
		protected abstract void run() throws Exception;
		protected void fail(Exception e) throws Exception {
			throw e;
		}
	}
	
    public RunTests() throws IOException {
	}

	public static void main(String[] args) throws IOException {
    	RunTests app = new RunTests();
		int exitstatus = app.run(args);
    	System.exit(exitstatus);
	}
	
	public int run(String[] args) {
		File source = null;
		String onexit = "--onsuccess";
		int exitstatus = 0;
    	try {
    		for (int i = 0; i < args.length; i++) {
				source = runScript(args[i]); 
			}
    	} catch (Exception e) {
    		e.printStackTrace();
    		onexit = "--onfail";
    		exitstatus = 1;
    	}
    	
    	// If we have an onexit handler to call, then run it now
    	if (null != onexit) {
    		try {
				executeFunction(onexit, source, null, null);
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
				runCommand(tokenizer, file, file.getName(), new ExecutionContext());
			}
		}
		return file;
	}
	
	private boolean executeFunction(String name, File file, StreamTokenizer parent, ExecutionContext script) throws Exception {
		ExecutionContext context = functions.get(name);
		if (null != context) {
			// If this context has parameters then gather argument values
			if (null != parent && null != script) {
				List<String> params = context.getParams();
				if (null != params && params.size() > 0) {
					context.clearArgs();
					int count = params.size();
					while (count-- > 0) {
						// get argument
						parent.nextToken();
						System.out.print(' ');
						switch(parent.ttype) {
						case StreamTokenizer.TT_NUMBER:
							System.out.print(parent.nval);
							context.addArg(parent.nval);
							break;
						default:
							System.out.print(parent.sval);
							context.addArg(script.getExpandedString(parent));
							break;
						}
					}
				}
				System.out.println();
			}
			
			// Get function body and execute it
			String code = context.getBody();
			if (null != code) {
				StreamTokenizer tokenizer = openString(code);
				while (tokenizer.nextToken() != StreamTokenizer.TT_EOF) {
					if (tokenizer.ttype == StreamTokenizer.TT_WORD) {
						runCommand(tokenizer, file, name, context);
					}
				}		
				return true;
			}
		}
		return false;
	}
	
	private boolean runString(String source, File file, String cmd) throws Exception {
		StreamTokenizer tokenizer = openString(source);
		while (tokenizer.nextToken() != StreamTokenizer.TT_EOF) {
			if (tokenizer.ttype == StreamTokenizer.TT_WORD) {
				runCommand(tokenizer, file, cmd, null);
			}
		}
		return true;		
	}
	
	// Argument parsing: { token token { token token } token }
	
	private interface BlockHandler {
		void parseToken(StreamTokenizer tokenizer, String arg) throws IOException;
	}

	private void parseBlock(StreamTokenizer tokenizer, BlockHandler handler) throws Exception {
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
						System.out.print("}");
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
				handler.parseToken(tokenizer, arg);
				tokenizer.nextToken();
			}
			System.out.println();			
			throw new Exception("args unexpectd token " + tokenizer.ttype);
		}
		tokenizer.pushBack();		// no arguments
	}
	
	private class Block implements BlockHandler {
		char sep = ' ';
		char isep = sep;
		boolean quoteWords = false;
		String args = null;
		public Block(char sep, boolean quoteWords) {
			this.isep = this.sep = sep;
			this.quoteWords = quoteWords;
		}
		public void parseToken(StreamTokenizer tokenizer, String arg) {
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

	private class ArgArray implements BlockHandler {
		List<String> args = new ArrayList<String>();
		public void parseToken(StreamTokenizer tokenizer, String arg) {
			if (tokenizer.ttype == '"' || tokenizer.ttype == '\'') {
				arg = arg.substring(1,arg.length()-1);
			}
			args.add(arg);
		}
		List<String> get() {
			return args;
		}
	};

	private String getBlock(StreamTokenizer tokenizer, char sep, boolean quoteWords) throws Exception {
		Block block = new Block(sep, quoteWords);
		parseBlock(tokenizer, block);
		return block.get();
	}

	private List<String> getArgs(StreamTokenizer tokenizer) throws Exception {
		ArgArray args = new ArgArray();
		parseBlock(tokenizer, args);
		return args.get();
	}
	
	// Param parsing: (name, name, name)

	private interface ParamHandler {
		void processParam(StreamTokenizer tokenizer, String arg) throws IOException;
	}

	private void parseParams(StreamTokenizer tokenizer, ParamHandler handler) throws Exception {
		tokenizer.nextToken();
		if (tokenizer.ttype == '(') {
			System.out.print(" (");
			tokenizer.nextToken();
			while (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == ',' || tokenizer.ttype == ')') {
				System.out.print(' ');
				String arg;
				switch(tokenizer.ttype){ 
				case ',': arg = ","; break;
				case ')':
					System.out.print(")");
					return;
				default:
					arg = tokenizer.sval;
				}
				System.out.print(arg);
				handler.processParam(tokenizer, arg);
				tokenizer.nextToken();
			}
			System.out.println();			
			throw new Exception("args unexpectd token " + tokenizer.ttype);
		}
		tokenizer.pushBack();		// no arguments
	}
	
	private class Params implements ParamHandler {
		List<String> args = new ArrayList<String>();
		public Params() {
		}
		public void processParam(StreamTokenizer tokenizer, String arg) throws IOException {
			if (!arg.equals(",")) {
				args.add(arg);
			}
		}
		List<String> get() {
			return args;
		}
	};
	
	private List<String> getParams(StreamTokenizer tokenizer) throws Exception {
		Params params = new Params();
		parseParams(tokenizer, params);
		return params.get();
	}
	
	private void runCommand(final StreamTokenizer tokenizer, File file, String source, ExecutionContext script) throws Exception {
		// Automatic log dumping
		if (autolog && null != driver) {
			dumpLog();
		}
		
		String cmd = tokenizer.sval;
		System.out.printf((new Date()).getTime() + ": [%s,%d] ", source, tokenizer.lineno());
		System.out.print(tokenizer.sval);
		
		if (cmd.equals("version")) {
			// HELP: version
			System.out.println();
			System.out.println("ScriptDriver version " + version);
			return;
		}

		if (cmd.equals("browser")) {
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				System.out.print(' ');
				System.out.print(tokenizer.sval);
				
				if (tokenizer.sval.equals("prefs")) {
					// HELP: browser prefs ...
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
					// HELP: browser option ...
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
					// HELP: browser start
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
					// HELP: browser get "url"
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
					// HELP: browser close
					System.out.println();
					if (!_skip) {
						driver.close();
						autolog = false;
					}
					return;
				}
				
				if (tokenizer.sval.equals("chrome")) {
					// HELP: browser chrome <width>,<height>
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
								if (!_skip) {
									this.chrome = new Dimension(w,h);
								}
								return;
							}
						}
					}
					throw new Exception("browser chrome arguments error at line " + tokenizer.lineno());
				}
				
				if (tokenizer.sval.equals("size")) {
					// HELP: browser size <width>,<height>
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
								if (!_skip) {
									Dimension size = new Dimension(this.chrome.width + w, this.chrome.height + h);
									System.out.println("// chrome " + this.chrome.toString());
									System.out.println("// size with chrome " + size.toString());
									driver.manage().window().setSize(size);
								}
								return;
							}
						}
					}
					throw new Exception("browser size arguments error at line " + tokenizer.lineno());
				}
				if (tokenizer.sval.equals("pos")) {
					// HELP: browser pos <x>,<y>
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
		
		if (cmd.equals("alias") || cmd.equals("function")) {
			// HELP: alias <name> { body }
			// HELP: function <name> (param, ...) { body }
			String name = null, args = null;
			List<String> params = null;
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				System.out.print(' ');
				System.out.print(tokenizer.sval);
				name = tokenizer.sval;
				params = getParams(tokenizer);
				args = getBlock(tokenizer, ' ', false);
				System.out.println();
				if (_skip) return;
				addFunction(name, params, args);		// add alias
				return;
			}
			System.out.println();			
			throw new Exception("alias name expected");
		}

		if (cmd.equals("while")) {
			// HELP: while { block }
			String block = null;
			block = getBlock(tokenizer, ' ', false);
			if (_skip) return;
			boolean exitloop = false;
			while (!exitloop) {
				try {
					runString(block, file, "while");
				} catch(Exception e) {
					exitloop = true;
				}
			}
			return;
		}

		if (cmd.equals("include")) {
			// HELP: include <script>
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
			// HELP: exec <command> { args ... }
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
			// HELP: exec-include <command> { args ... }
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
					// HELP: log dump
					System.out.println("");
					if (driver != null) dumpLog();
					return;
				}
				if (action.equals("auto")) {
					// HELP: log auto <on|off>
					// HELP: log auto <true|false>
					tokenizer.nextToken();
					String onoff = tokenizer.sval;
					System.out.print(' ');
					System.out.println(onoff);
					autolog = onoff.equals("on") || onoff.equals("true");
					return;
				}
				System.out.println();
				throw new Exception("invalid log action");
			}
			System.out.println();
			throw new Exception("log argument should be string or a word");
		}
		
		if (cmd.equals("default")) {
			// HELP: default wait <seconds>
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				String action = tokenizer.sval;
				System.out.print(' ');
				System.out.print(action);
				if (action.equals("wait")) {
					tokenizer.nextToken();
					if (tokenizer.ttype == StreamTokenizer.TT_NUMBER || tokenizer.ttype == '*') {
						System.out.print(' ');
						System.out.println(tokenizer.nval);
						_defaultWaitFor = (int) (tokenizer.nval * 1000.0);
					}
					return;
				}
				System.out.println();
				throw new Exception("invalid default property");
			}
			System.out.println();
			throw new Exception("default argument should be string or a word");
		}

		if (cmd.equals("push")) {
			// HELP: push wait
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				String action = tokenizer.sval;
				System.out.print(' ');
				System.out.print(action);
				ArrayList<Object> stack = stacks.get(action);
				if (null == stack) {
					stack = new ArrayList<Object>();
					stacks.put(action, stack);
				}
				if (action.equals("wait")) {
					stack.add(new Long(_waitFor));
					System.out.println();
					return;
				}
			}
			System.out.println();
			throw new Error("Invalid push argument");
		}
				
		if (cmd.equals("pop")) {
			// HELP: pop wait
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				String action = tokenizer.sval;
				System.out.print(' ');
				System.out.print(action);
				ArrayList<Object> stack = stacks.get(action);
				if (null == stack || stack.isEmpty()) {
					throw new Error("pop called without corresponding push");
				}
				if (action.equals("wait")) {
					int index = stack.size()-1;
					_waitFor = (Long) stack.get(index);
					stack.remove(index);
					System.out.println();
					return;
				}
			}
			System.out.println();
			throw new Error("Invalid push argument");
		}

		if (cmd.equals("echo")) {
			// HELP: echo "string"
			tokenizer.nextToken();
			if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
				String text = script.getExpandedString(tokenizer);
				System.out.print(' ');
				System.out.println(text);
				if (!_skip) System.out.println(text);
				return;
			}
			System.out.println();
			throw new Exception("echo argument should be string or a word");
		}

		if (null != driver) {
			
			// all these command require the browser to have been started
	
			if (cmd.equals("field") || cmd.equals("id") || cmd.equals("test-id")) {
				// HELP: field "<test-id>"
				// HELP: id "<test-id>"
				// HELP: test-id "<test-id>"
				tokenizer.nextToken();
				if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
					if (_skip) return;
					System.out.print(' ');
					this.setContextToField(script, tokenizer);
					return;
				}
				System.out.println();
				throw new Exception(cmd + " command requires a form.field argument");
			}
	
			if (cmd.equals("select")) {
				// HELP: select "<query-selector>"
				tokenizer.nextToken();
				if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
					if (_skip) return;
					System.out.print(' ');
					selectContext(tokenizer, script);
					return;
				}
				System.out.println();
				throw new Exception(cmd + " command requires a css selector argument");
			}
			
			if (cmd.equals("xpath")) {
				// HELP: xpath "<xpath-expression>"
				tokenizer.nextToken();
				if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
					if (_skip) return;
					System.out.print(' ');
					this.xpathContext(script, tokenizer);
					return;
				}
				System.out.println();
				throw new Exception(cmd + " command requires a css selector argument");
			}
			
			if (cmd.equals("wait")) {
				// HELP: wait <seconds>
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
				// HELP: if <commands> then <commands> [else <commands>] endif
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
				// HELP: not <check-command>
				System.out.println();
				_not = true;
				return;
			}
	
			if (cmd.equals("set") || cmd.equals("send")) {
				// HELP: set "<value>"
				// HELP: send "<value>"
				tokenizer.nextToken();
				if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
					if (_skip) return;
					System.out.print(' ');
					System.out.println(script.getExpandedString(tokenizer));
					this.setContextValue(cmd, script, tokenizer, cmd.equals("set"));
					return;
				}
				System.out.println();
				throw new Exception("set command requires a value argument");
			}
	
			if (cmd.equals("test") || cmd.equals("check")) {
				// HELP: test "<value>"
				// HELP: check "<value>"
				tokenizer.nextToken();
				if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"' || tokenizer.ttype == '\'') {
					if (_skip) return;
					System.out.print(' ');
					System.out.println(script.getExpandedString(tokenizer));
					this.testContextValue(cmd, script, tokenizer, false);
					return;
				}
				System.out.println();
				throw new Exception(cmd + " command requires a value argument");
			}
			
			if (cmd.equals("checksum")) {
				// HELP: checksum "<checksum>"
				tokenizer.nextToken();
				if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"' || tokenizer.ttype == '\'') {
					if (_skip) return;
					System.out.print(' ');
					System.out.println(script.getExpandedString(tokenizer));
					this.testContextValue(cmd, script, tokenizer, true);
					return;
				}
				System.out.println();
				throw new Exception(cmd + " command requires a value argument");
			}
	
			if (cmd.equals("click")) {
				// HELP: click
				System.out.println();
				new WaitFor(cmd, tokenizer, true) {
					@Override
					protected void run() {
						if (!_skip) selection.click();
					}
				};
				return;
			}
			
			if (cmd.equals("scroll-into-view")) {
				// HELP: scroll-into-view
				System.out.println();
				if (null == selection) throw new Exception(cmd + " command requires a field selection at line " + tokenizer.lineno());
				if (!_skip) {
					try {
						scrollContextIntoView(selection);
					} catch(Exception e) {
						System.out.println(e.getMessage());
						info(selection, selectionCommand, false);
						throw e;
					}
				}
				return;			
			}
	
			if (cmd.equals("clear")) {
				// HELP: clear
				System.out.println();
				new WaitFor(cmd, tokenizer, true) {
					@Override
					protected void run() {
						if (!_skip) selection.clear();
					}
				};
				return;
			}
			
			if (cmd.equals("call")) {
				// HELP: call <function> { args ... }
				String function = null, args = null;
				tokenizer.nextToken();
				if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {		// expect a quoted string
					function = tokenizer.sval;
					System.out.print(' ');
					System.out.print(function);
					args = getBlock(tokenizer, ',', true);
					if (_skip) return;
					if (null == args) args = "";
					String js = "var result = window.RegressionTest.test('"+function+"',[" + args + "]);"
									+ "arguments[arguments.length-1](result);";
					System.out.println(js);
					Object result = driver.executeAsyncScript(js);
					if (null != result) {
						if (result.getClass() == RemoteWebElement.class) {
							selection = (RemoteWebElement) result;
							stype = SelectionType.Script;
							selector = js;
							System.out.println("new selection " + selection);
						}
					}
					return;
				}
				System.out.println();
				throw new Exception("missing arguments for call statement at line " + tokenizer.lineno());
			}
			
			if (cmd.equals("enabled")) {
				// HELP: enabled
				System.out.println();
				new WaitFor(cmd, tokenizer, true) {
					@Override
					protected void run() throws RetryException {
						if (!_skip || selection.isEnabled() != _not) {
							_not = false;
							return;
						}
						throw new RetryException("enabled check failed");
					}
				};
				return;
			}
	
			if (cmd.equals("selected")) {
				// HELP: selected
				System.out.println();
				new WaitFor(cmd, tokenizer, true) {
					@Override
					protected void run() throws RetryException {
						if (_skip || selection.isSelected() != _not) {
							_not = false;
							return;
						}
						throw new RetryException("selected check failed");
					}
				};
				return;
			}
			
			if (cmd.equals("displayed")) {
				// HELP: displayed
				System.out.println();
				new WaitFor(cmd, tokenizer, true) {
					@Override
					protected void run() throws RetryException {
						if (_skip || selection.isDisplayed() != _not) {
							_not = false;
							return;
						}
						throw new RetryException("displayed check failed");
					}
				};
				return;
			}
			
			if (cmd.equals("at")) {
				// HELP: at <x|*>,<y>
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
							final int X = x;
							final int Y = y;
							new WaitFor(cmd, tokenizer, true) {
								@Override
								protected void run() throws RetryException {
									Point loc = selection.getLocation();
									if (_skip || ((loc.x == X || X == -1) && loc.y == Y) != _not) {
										_not = false;
										return;
									}
									throw new RetryException("location check failed");
								}
							};
							return;
						}
					}
				}
				System.out.println();
				throw new Exception("at missing co-ordiantes at line " + tokenizer.lineno());
			}
	
			if (cmd.equals("size")) {
				// HELP: size <w|*>,<h>
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
							final int MW = mw;
							final int W = w;
							final int H = h;
							new WaitFor(cmd, tokenizer, true) {
								@Override
								protected void run() throws RetryException {
									Dimension size = selection.getSize();
									if (_skip || ((MW == -1 || (size.width >= MW && size.width <= W)) && size.height == H) != _not) {
										_not = false;
										return;
									}
									throw new RetryException("size check failed");
								}
							};
							return;
						}
					}
				}
				System.out.println();
				throw new Exception("size missing dimensions at line " + tokenizer.lineno());
			}
	
			if (cmd.equals("tag")) {
				// HELP: tag <tag-name>
				tokenizer.nextToken();
				if (tokenizer.ttype == StreamTokenizer.TT_WORD || tokenizer.ttype == '"') {
					System.out.print(' ');
					System.out.print(tokenizer.sval);
					System.out.println();
					new WaitFor(cmd, tokenizer, true) {
						@Override
						protected void run() throws RetryException {
							String tag = selection.getTagName();
							if (_skip || tokenizer.sval.equals(tag) != _not) {
								_not = false;
								return;
							}
							throw new RetryException("tag \"" + tokenizer.sval + "\" check failed, tag is " + tag + " at line " + tokenizer.lineno());
						}
					};
					return;
				}
				System.out.println();
				throw new Exception("tag command has missing tag name at line " + tokenizer.lineno());
			}
	
			if (cmd.equals("info")) {
				// HELP: info
				System.out.println();
				if (null == selection) throw new Exception("info command requires a selection at line " + tokenizer.lineno());
				info(selection, selectionCommand, true);
				return;
			}
			
			if (cmd.equals("alert")) {
				// HELP: alert accept
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
				// HELP: dump
				System.out.println();
				if (!_skip) dump();
				return;
			}
			
			if (cmd.equals("mouse")) {
				// HELP: mouse { <center|0,0|origin|body|down|up|click|+/-x,+/-y> commands ... }
				parseBlock(tokenizer, new BlockHandler() {
					public void parseToken(StreamTokenizer tokenizer, String token) {
						int l = token.length();
						if (token.equals("center")) {
							actions.moveToElement(selection);
						} else if ((l > 1 && token.substring(1,l-1).equals("0,0")) || token.equals("origin")) {
							actions.moveToElement(selection,0,0);
						} else if (token.equals("body")) {
							actions.moveToElement(driver.findElement(By.tagName("body")),0,0);
						} else if (token.equals("down")) {
							actions.clickAndHold();
						} else if (token.equals("up")) {
							actions.release();
						} else if (token.equals("click")) {
							actions.click();
						} else if (l > 1) {
							String [] a = token.substring(1,l-1).split(",");
							actions.moveByOffset(Integer.valueOf(a[0]), Integer.valueOf(a[1]));
						} else {
							// no-op
						}
					}						
				});
				System.out.println();
				actions.release();
				actions.build().perform();
				return;
			}
	
			if (cmd.equals("sleep")) {
				// HELP: sleep <seconds>
				tokenizer.nextToken();
				if (tokenizer.ttype == StreamTokenizer.TT_NUMBER) {
					System.out.print(' ');
					System.out.println(tokenizer.nval);
					Sleeper.sleepTight((long) (tokenizer.nval * 1000));
					return;
				}
				System.out.println();
				throw new Exception("sleep command argument should be a number");
			}
			
			if (cmd.equals("fail")) {
				// HELP: fail "<message>"
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
				// HELP: debugger
				System.out.println();
				Sleeper.sleepTightInSeconds(10);
				return;
			}
		}
		
		if (functions.containsKey(cmd)) {
			executeFunction(cmd, file, tokenizer, script);
			return;
		}

		if (null == driver) {
			throw new Exception("browser start must be used before attempt to interract with the browser");
		}
		
		System.out.println();
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
	
	private void addFunction(String name, List<String> params, String args) {
		ExecutionContext script = new ExecutionContext(params, args);
		functions.put(name, script);		
	}
	
	private boolean compareStrings(String s1, String s2, boolean checksum) {
		if (checksum) {
			CRC32 crc = new CRC32();
			crc.update(s1.getBytes());
			return ("crc32:"+crc.getValue()).equals(s2);
		}
		return s1.equals(s2);
	}

	private void testContextValue(String cmd, final ExecutionContext script, final StreamTokenizer tokenizer, final boolean checksum) throws Exception {
		new WaitFor(cmd, tokenizer, true) {
			@Override
			protected void run() throws RetryException {
				String tagName = selection.getTagName();
				String test = script.getExpandedString(tokenizer);
				if (tagName.equals("input") || tagName.equals("select") || tagName.equals("textarea")) {
					System.out.println("// Checking element value is " + (_not ? "NOT " : "") + " equal to '" + test + "'");
					String value = selection.getAttribute("value");
					if (_not != (null != value && compareStrings(value, test, checksum))) {
						_not = false;
						return;
					}
					if (null == value) {
						System.out.println("// CHECK FAIL: EXPECTED '" + test + "' BUT VALUE IS NULL");				
					} else {
						System.out.println("// CHECK FAIL: EXPECTED '" + test + "' WHICH DOES " + (_not ? "" : "NOT ") + " MATCH '" + value + "'");
					}
					throw new RetryException("value check failed");
				} else {
					System.out.println("// Checking element textContent is " + (_not ? "NOT " : "") + "equal to '" + test + "'");
						String value = selection.getText();
						if (_not != (null != value && compareStrings(value, test, checksum))) {
							_not = false;
							return;
						}
						if (null == value) {
							System.out.println("// CHECK FAIL: EXPECTED '" + test + "' BUT VALUE IS NULL");				
						} else {
							System.out.println("// CHECK FAIL: EXPECTED '" + test + "' WHICH DOES " + (_not ? "" : "NOT ") + " MATCH '" + value + "'");
						}
						throw new RetryException("textContent check failed");
				}
			}
			@Override
			protected void fail(Exception e) throws Exception {
				throw new Exception(e.getMessage() + ": " + tokenizer.sval + " test failed for current select selection at line " + tokenizer.lineno());
			}
		};
	}

	private void setContextValue(String cmd, final ExecutionContext script, final StreamTokenizer tokenizer, final boolean set) throws Exception {
		new WaitFor(cmd, tokenizer, true) {
			@Override
			protected void run() throws Exception {
				final String tagName = selection.getTagName();
				if (tagName.equals("input") || tagName.equals("select") || tagName.equals("textarea")) {
					if (set && !tagName.equals("select")) selection.clear();
					selection.sendKeys(script.getExpandedString(tokenizer));
				} else {
					throw new Exception("set cannot be used on a non-field selection at line " + tokenizer.lineno());
				}
			}
			@Override
			protected void fail(Exception e) throws Exception {
				throw new Exception("could not send keys to element: " + e.getMessage());
			}
		};
	}
	
	private void sleep(int ms) {
		Sleeper.sleepTight(ms);
	}
	
	private boolean sleepAndReselect(int ms) throws Exception {
		if (autolog) dumpLog();
		long waitTimer = (_waitFor - (new Date()).getTime());
		System.out.println("// SLEEP AND RESELECT [wait=" + waitTimer + "] ID " + selection.getId());
//		if (waitTimer < -(ms*2)) {
//			System.out.println("AUTO WAIT FOR 1s");
//			_waitFor = (new Date()).getTime() + 1000;
//		}
		Sleeper.sleepTight(ms);
		try {
			if (stype == SelectionType.XPath || stype == SelectionType.Field) {
				selection = (RemoteWebElement) driver.findElement(By.xpath(selector));
			}
			else if (stype == SelectionType.Select) {
				selection = (RemoteWebElement) driver.findElement(By.cssSelector(selector));
			}
			else if (stype == SelectionType.Script) {
				Object result = driver.executeAsyncScript(selector);
				if (null != result) {
					if (result.getClass() == RemoteWebElement.class) {
						selection = (RemoteWebElement) result;
					}
				}
			}
		} catch(NoSuchElementException e2) {
			// element has gone stale, re-select it
			System.out.println("// SLEEPANDRESELECT: EXCEPTION : NoSuchElement");
		} catch (Exception e) {
			System.out.println("// SLEEPANDRESELECT: EXCEPTION : " + e.getMessage());
			throw e;
		}
		System.out.println("// SLEEPANDRESELECT: RESELECTED " + selector + " SLEEP 100ms");
		Sleeper.sleepTight(100);  // small delay
		return true;
	}

	private void xpathContext(ExecutionContext script, StreamTokenizer tokenizer) throws Exception {
		Exception e;
		stype = SelectionType.None;
		selector = null;
		String sval = script.getExpandedString(tokenizer);
		System.out.println(sval);
		do {
			try {
				selection = (RemoteWebElement) driver.findElement(By.xpath(sval));
				if (_not) { 
					_test = _not = false; 
					throw new Exception("not xpath " + sval + " is invalid on line " + tokenizer.lineno()); 
				}
				selectionCommand = "xpath \"" + sval + "\"";
				stype = SelectionType.XPath;
				selector = sval;
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
			if (_not) { _test = true; selection = null; _not = false; return; }
			throw new Exception("xpath " + sval + " is invalid on line " + tokenizer.lineno() + " " + e.getMessage());
		}
	}

	private void selectContext(StreamTokenizer tokenizer, ExecutionContext script) throws Exception {
		Exception e;
		stype = SelectionType.None;
		selector = null;
		String sval = script.getExpandedString(tokenizer);
		System.out.println(sval);
		do {
			try {
				selection = (RemoteWebElement) driver.findElement(By.cssSelector(sval));
				if (_not) {
					_test = _not = false; 
					throw new Exception("not selector " + sval + " is invalid on line " + tokenizer.lineno()); 
				}
				selectionCommand = "select \"" + sval + "\"";
				stype = SelectionType.Select;
				selector = sval;
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
			if (_not) { _test = true; selection = null; selector = null; _not = false; return; }
			throw new Exception("selector " + sval + " is invalid on line " + tokenizer.lineno() + " " + e.getMessage());
		}
	}

	private void setContextToField(ExecutionContext script, StreamTokenizer tokenizer) throws Exception {
		Exception e;
		String sval = script.getExpandedString(tokenizer);
		System.out.println(sval);
		String query = "//*[@test-id='"+sval+"']";
		stype = SelectionType.None;
		selector = null;
		do {
			try {
				selection = (RemoteWebElement) driver.findElement(By.xpath(query));
				if (_not) { 
					_test = _not = false; 
					throw new Exception("not test-id " + sval + " is invalid on line " + tokenizer.lineno()); 
				}
				selectionCommand = "field \"" + sval + "\"";
				stype = SelectionType.Field;
				selector = query;
				_test = true;
				return;
			} catch(Exception ex) {
				e = ex;
				sleep(100);
			}
		} while (this._waitFor > 0 && (new Date()).getTime() < this._waitFor);
		_waitFor = 0;
		_test = false;
		if (!_if) {
			if (_not) { _test = true; selection = null; _not = false; return; }
			throw new Exception("field reference " + sval + " is invalid on line " + tokenizer.lineno() + " " + e.getMessage());
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