/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.databricks.labs.log.appenders;

import org.apache.log4j.Appender;
import org.apache.log4j.Category;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Layout;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.spi.AppenderAttachable;
import org.apache.log4j.spi.LocationInfo;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.spi.ThrowableInformation;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

public class JsonLayout extends Layout {
  private static final Pattern SEP_PATTERN = Pattern.compile("(?:\\p{Space}*?[,;]\\p{Space}*)+");
  private static final Pattern PAIR_SEP_PATTERN =
      Pattern.compile("(?:\\p{Space}*?[:=]\\p{Space}*)+");

  private static final char[] HEX_CHARS = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
  };
  private static final String VERSION = "1";
  private final Map<String, String> fields;
  private final DateFormat dateFormat;
  private final Date date;
  private String tagsVal;
  private String fieldsVal;
  private String includedFields;
  private String excludedFields;
  private String[] renamedFieldLabels;
  private RenderedFieldLabels renderedFieldLabels = new RenderedFieldLabels();
  private String[] tags;
  private String path;
  private boolean pathResolved;
  private String hostName;
  private boolean ignoresThrowable;
  public JsonLayout() {
    fields = new HashMap<String, String>();

    dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

    date = new Date();
  }

  @Override
  public String format(LoggingEvent event) {
    final StringBuilder buf = new StringBuilder(2 * 1024);

    buf.append('{');

    boolean hasPrevField = false;
    if (renderedFieldLabels.exception.isEnabled) {
      hasPrevField = appendException(buf, event);
    }

    if (hasPrevField) {
      buf.append(',');
    }
    hasPrevField = appendFields(buf, event);

    if (renderedFieldLabels.level.isEnabled) {
      if (hasPrevField) {
        buf.append(',');
      }
      appendField(buf, renderedFieldLabels.level.renderedLabel, event.getLevel().toString());
      hasPrevField = true;
    }

    if (renderedFieldLabels.location.isEnabled) {
      if (hasPrevField) {
        buf.append(',');
      }
      hasPrevField = appendLocation(buf, event);
    }

    if (renderedFieldLabels.logger.isEnabled) {
      if (hasPrevField) {
        buf.append(',');
      }
      appendField(buf, renderedFieldLabels.logger.renderedLabel, event.getLoggerName());
      hasPrevField = true;
    }

    if (renderedFieldLabels.message.isEnabled) {
      if (hasPrevField) {
        buf.append(',');
      }
      appendField(buf, renderedFieldLabels.message.renderedLabel, event.getRenderedMessage());
      hasPrevField = true;
    }

    if (renderedFieldLabels.mdc.isEnabled) {
      if (hasPrevField) {
        buf.append(',');
      }
      hasPrevField = appendMDC(buf, event);
    }

    if (renderedFieldLabels.ndc.isEnabled) {
      String ndc = event.getNDC();
      if (ndc != null && !ndc.isEmpty()) {
        if (hasPrevField) {
          buf.append(',');
        }
        appendField(buf, renderedFieldLabels.ndc.renderedLabel, event.getNDC());
        hasPrevField = true;
      }
    }

    if (renderedFieldLabels.host.isEnabled) {
      if (hasPrevField) {
        buf.append(',');
      }
      appendField(buf, renderedFieldLabels.host.renderedLabel, hostName);
      hasPrevField = true;
    }

    if (renderedFieldLabels.path.isEnabled) {
      if (hasPrevField) {
        buf.append(',');
      }
      hasPrevField = appendSourcePath(buf, event);
    }

    if (renderedFieldLabels.tags.isEnabled) {
      if (hasPrevField) {
        buf.append(',');
      }
      hasPrevField = appendTags(buf, event);
    }

    if (renderedFieldLabels.timestamp.isEnabled) {
      if (hasPrevField) {
        buf.append(',');
      }
      date.setTime(event.getTimeStamp());
      appendField(buf, renderedFieldLabels.timestamp.renderedLabel, dateFormat.format(date));
      hasPrevField = true;
    }

    if (renderedFieldLabels.thread.isEnabled) {
      if (hasPrevField) {
        buf.append(',');
      }
      appendField(buf, renderedFieldLabels.thread.renderedLabel, event.getThreadName());
      hasPrevField = true;
    }

    if (renderedFieldLabels.version.isEnabled) {
      if (hasPrevField) {
        buf.append(',');
      }
      appendField(buf, renderedFieldLabels.version.renderedLabel, VERSION);
    }

    buf.append("}\n");

    return buf.toString();
  }

  @SuppressWarnings("UnusedParameters")
  private boolean appendFields(StringBuilder buf, LoggingEvent event) {
    if (fields.isEmpty()) {
      return false;
    }

    for (Iterator<Map.Entry<String, String>> iter = fields.entrySet().iterator();
        iter.hasNext(); ) {
      Map.Entry<String, String> entry = iter.next();
      appendField(buf, entry.getKey(), entry.getValue());
      if (iter.hasNext()) {
        buf.append(',');
      }
    }

    return true;
  }

  private boolean appendSourcePath(StringBuilder buf, LoggingEvent event) {
    if (!pathResolved) {
      @SuppressWarnings("unchecked")
      Appender appender = findLayoutAppender(event.getLogger());
      if (appender instanceof FileAppender) {
        FileAppender fileAppender = (FileAppender) appender;
        path = getAppenderPath(fileAppender);
      }
      pathResolved = true;
    }
    if (path != null) {
      appendField(buf, renderedFieldLabels.path.renderedLabel, path);
      return true;
    }
    return false;
  }

  private Appender findLayoutAppender(Category logger) {
    for (Category parent = logger; parent != null; parent = parent.getParent()) {
      @SuppressWarnings("unchecked")
      Appender appender = findLayoutAppender(parent.getAllAppenders());
      if (appender != null) {
        return appender;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private Appender findLayoutAppender(Enumeration<? extends Appender> appenders) {
    if (appenders == null) {
      return null;
    }

    while (appenders.hasMoreElements()) {
      Appender appender = appenders.nextElement();
      // get the first appender with this layout instance and ignore others;
      if (appender.getLayout() == this) {
        return appender;
      }
      if (appender instanceof AppenderAttachable) {
        AppenderAttachable appenderContainer = (AppenderAttachable) appender;
        return findLayoutAppender(appenderContainer.getAllAppenders());
      }
    }
    return null;
  }

  private String getAppenderPath(FileAppender fileAppender) {
    String path = null;
    try {
      String fileName = fileAppender.getFile();
      if (fileName != null && !fileName.isEmpty()) {
        path = new File(fileName).getCanonicalPath();
      }
    } catch (IOException e) {
      LogLog.error("Unable to retrieve appender's file name", e);
    }
    return path;
  }

  @SuppressWarnings("UnusedParameters")
  private boolean appendTags(StringBuilder builder, LoggingEvent event) {
    if (tags == null || tags.length == 0) {
      return false;
    }

    appendQuotedName(builder, renderedFieldLabels.tags.renderedLabel);
    builder.append(":[");
    for (int i = 0, len = tags.length; i < len; i++) {
      appendQuotedValue(builder, tags[i]);
      if (i != len - 1) {
        builder.append(',');
      }
    }
    builder.append(']');

    return true;
  }

  private boolean appendMDC(StringBuilder buf, LoggingEvent event) {
    Map<?, ?> entries = event.getProperties();
    if (entries.isEmpty()) {
      return false;
    }

    appendQuotedName(buf, renderedFieldLabels.mdc.renderedLabel);
    buf.append(":{");

    for (Iterator<? extends Map.Entry<?, ?>> iter = entries.entrySet().iterator();
        iter.hasNext(); ) {
      Map.Entry<?, ?> entry = iter.next();
      appendField(buf, entry.getKey(), entry.getValue());
      if (iter.hasNext()) {
        buf.append(',');
      }
    }
    buf.append('}');

    return true;
  }

  private boolean appendLocation(StringBuilder buf, LoggingEvent event) {
    LocationInfo locationInfo = event.getLocationInformation();
    if (locationInfo == null) {
      return false;
    }

    boolean hasPrevField = false;

    appendQuotedName(buf, renderedFieldLabels.location.renderedLabel);
    buf.append(":{");

    String className = locationInfo.getClassName();
    if (className != null) {
      appendField(buf, renderedFieldLabels.locationClass.renderedLabel, className);
      hasPrevField = true;
    }

    String fileName = locationInfo.getFileName();
    if (fileName != null) {
      if (hasPrevField) {
        buf.append(',');
      }
      appendField(buf, renderedFieldLabels.locationFile.renderedLabel, fileName);
      hasPrevField = true;
    }

    String methodName = locationInfo.getMethodName();
    if (methodName != null) {
      if (hasPrevField) {
        buf.append(',');
      }
      appendField(buf, renderedFieldLabels.locationMethod.renderedLabel, methodName);
      hasPrevField = true;
    }

    String lineNum = locationInfo.getLineNumber();
    if (lineNum != null) {
      if (hasPrevField) {
        buf.append(',');
      }
      appendField(buf, renderedFieldLabels.locationLine.renderedLabel, lineNum);
    }

    buf.append('}');

    return true;
  }

  private boolean appendException(StringBuilder buf, LoggingEvent event) {
    ThrowableInformation throwableInfo = event.getThrowableInformation();
    if (throwableInfo == null) {
      return false;
    }

    appendQuotedName(buf, renderedFieldLabels.exception.renderedLabel);
    buf.append(":{");

    boolean hasPrevField = false;

    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    Throwable throwable = throwableInfo.getThrowable();
    if (throwable != null) {
      String message = throwable.getMessage();
      if (message != null) {
        appendField(buf, renderedFieldLabels.exceptionMessage.renderedLabel, message);
        hasPrevField = true;
      }

      String className = throwable.getClass().getCanonicalName();
      if (className != null) {
        if (hasPrevField) {
          buf.append(',');
        }
        appendField(buf, renderedFieldLabels.exceptionClass.renderedLabel, className);
        hasPrevField = true;
      }
    }

    String[] stackTrace = throwableInfo.getThrowableStrRep();
    if (stackTrace != null && stackTrace.length != 0) {
      if (hasPrevField) {
        buf.append(',');
      }
      appendQuotedName(buf, renderedFieldLabels.exceptionStacktrace.renderedLabel);
      buf.append(":\"");
      for (int i = 0, len = stackTrace.length; i < len; i++) {
        appendValue(buf, stackTrace[i]);
        if (i != len - 1) {
          appendChar(buf, '\n');
        }
      }
      buf.append('\"');
    }

    buf.append('}');

    return true;
  }

  @Override
  public boolean ignoresThrowable() {
    return ignoresThrowable;
  }

  public void activateOptions() {
    renderedFieldLabels = new RenderedFieldLabels();

    if (includedFields != null) {
      String[] included = SEP_PATTERN.split(includedFields);
      for (String val : included) {
        renderedFieldLabels.enable(val);
      }
    }
    if (renamedFieldLabels != null) {
      for (String fieldLabel : renamedFieldLabels) {
        String[] field = PAIR_SEP_PATTERN.split(fieldLabel);

        if (field.length == 2) {
          renderedFieldLabels.updateLabel(field[0], field[1]);
        }
      }
    }
    if (excludedFields != null) {
      String[] excluded = SEP_PATTERN.split(excludedFields);
      for (String val : excluded) {
        renderedFieldLabels.disable(val);
      }
    }
    if (tagsVal != null) {
      tags = SEP_PATTERN.split(tagsVal);
    }
    if (fieldsVal != null) {
      String[] fields = SEP_PATTERN.split(fieldsVal);
      for (String fieldVal : fields) {
        String[] field = PAIR_SEP_PATTERN.split(fieldVal);
        this.fields.put(field[0], field[1]);
      }
    }
    if (hostName == null) {
      try {
        hostName = InetAddress.getLocalHost().getHostName();
      } catch (UnknownHostException e) {
        hostName = "localhost";
        LogLog.error("Unable to determine name of the localhost", e);
      }
    }
    ignoresThrowable = !renderedFieldLabels.exception.isEnabled;
  }

  @Override
  public String getContentType() {
    return "application/json";
  }

  private void appendQuotedName(StringBuilder out, Object name) {
    out.append('\"');
    appendValue(out, String.valueOf(name));
    out.append('\"');
  }

  private void appendQuotedValue(StringBuilder out, Object val) {
    out.append('\"');
    appendValue(out, String.valueOf(val));
    out.append('\"');
  }

  private void appendValue(StringBuilder out, String val) {
    for (int i = 0, len = val.length(); i < len; i++) {
      appendChar(out, val.charAt(i));
    }
  }

  private void appendField(StringBuilder out, Object name, Object val) {
    appendQuotedName(out, name);
    out.append(':');
    appendQuotedValue(out, val);
  }

  private void appendChar(StringBuilder out, char ch) {
    switch (ch) {
      case '"':
        out.append("\\\"");
        break;
      case '\\':
        out.append("\\\\");
        break;
      case '/':
        out.append("\\/");
        break;
      case '\b':
        out.append("\\b");
        break;
      case '\f':
        out.append("\\f");
        break;
      case '\n':
        out.append("\\n");
        break;
      case '\r':
        out.append("\\r");
        break;
      case '\t':
        out.append("\\t");
        break;
      default:
        if ((ch <= '\u001F')
            || ('\u007F' <= ch && ch <= '\u009F')
            || ('\u2000' <= ch && ch <= '\u20FF')) {
          out.append("\\u")
              .append(HEX_CHARS[ch >> 12 & 0x000F])
              .append(HEX_CHARS[ch >> 8 & 0x000F])
              .append(HEX_CHARS[ch >> 4 & 0x000F])
              .append(HEX_CHARS[ch & 0x000F]);
        } else {
          out.append(ch);
        }
        break;
    }
  }

  public void setTags(String tags) {
    this.tagsVal = tags;
  }

  public void setFields(String fields) {
    this.fieldsVal = fields;
  }

  public void setIncludedFields(String includedFields) {
    this.includedFields = includedFields;
  }

  public void setExcludedFields(String excludedFields) {
    this.excludedFields = excludedFields;
  }

  public void setHostName(String hostName) {
    this.hostName = hostName;
  }

  public void setRenamedFieldLabels(String renamedFieldLabels) {
    this.renamedFieldLabels = SEP_PATTERN.split(renamedFieldLabels);
  }

  private class LoggerField {
    private String defaultLabel;
    private String renderedLabel;
    private boolean isEnabled = true;

    LoggerField(String defaultName) {
      this.defaultLabel = defaultName;
      this.renderedLabel = defaultName;
    }

    LoggerField(String defaultName, String renderedLabel) {
      this.defaultLabel = defaultName;
      this.renderedLabel = renderedLabel;
    }

    void updateRenderedLabel(String label) {
      renderedLabel = label;
    }

    void disable() {
      isEnabled = false;
    }

    void enable() {
      isEnabled = true;
    }
  }

  private class RenderedFieldLabels {
    private final ArrayList<LoggerField> allFields = new ArrayList<LoggerField>();

    final LoggerField locationClass = loggerfield("location.class", "class");
    final LoggerField locationFile = loggerfield("location.file", "file");
    final LoggerField locationLine = loggerfield("location.line", "line");
    final LoggerField locationMethod = loggerfield("location.method", "method");

    final LoggerField exceptionClass = loggerfield("exception.class", "class");
    final LoggerField exceptionMessage = loggerfield("exception.message", "message");
    final LoggerField exceptionStacktrace = loggerfield("exception.stacktrace", "stacktrace");

    final LoggerField exception = loggerfield("exception");
    final LoggerField level = loggerfield("level");
    final LoggerField location = loggerfield("location");
    final LoggerField logger = loggerfield("logger");
    final LoggerField message = loggerfield("message");
    final LoggerField mdc = loggerfield("mdc");
    final LoggerField ndc = loggerfield("ndc");
    final LoggerField host = loggerfield("host");
    final LoggerField path = loggerfield("path");
    final LoggerField tags = loggerfield("tags");
    final LoggerField timestamp = loggerfield("@timestamp");
    final LoggerField thread = loggerfield("thread");
    final LoggerField version = loggerfield("@version");

    RenderedFieldLabels() {
      location.disable(); // By default location is not enabled as it's pretty expensive to resolve
    }

    private LoggerField loggerfield(String defaultName) {
      return loggerfield(defaultName, null);
    }

    private LoggerField loggerfield(String defaultName, String renderedLabel) {
      LoggerField loggerField;

      if (renderedLabel != null) {
        loggerField = new LoggerField(defaultName, renderedLabel);
      } else {
        loggerField = new LoggerField(defaultName);
      }

      allFields.add(loggerField);

      return loggerField;
    }

    void disable(String fieldName) {
      for (LoggerField field : allFields) {
        if (field.defaultLabel.startsWith(fieldName)) {
          field.disable();
        }
      }
    }

    void enable(String fieldName) {
      for (LoggerField field : allFields) {
        if (field.defaultLabel.startsWith(fieldName)) {
          field.enable();
        }
      }
    }

    void updateLabel(String fieldName, String newLabel) {
      for (LoggerField field : allFields) {
        if (field.defaultLabel.equals(fieldName)) {
          field.updateRenderedLabel(newLabel);
        }
      }
    }
  }
}
