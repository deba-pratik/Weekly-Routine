import javax.swing.*;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.List;
import java.util.logging.*;

// ---------------- Logging Setup ----------------
class Log {
    static final Logger UI = Logger.getLogger("weekly.ui");
    static final Logger PERSIST = Logger.getLogger("weekly.persistence");
    static final Logger TRACK = Logger.getLogger("weekly.tracking");
    static {
        try {
            Logger root = Logger.getLogger("");
            for (Handler h : root.getHandlers()) root.removeHandler(h);
            ConsoleHandler ch = new ConsoleHandler();
            ch.setLevel(Level.INFO);
            ch.setFormatter(new SimpleFormatter());
            root.addHandler(ch);
            File logFile = new File(System.getProperty("user.home"), "weekly_routine.log");
            FileHandler fh = new FileHandler(logFile.getAbsolutePath(), 1024*1024, 3, true);
            fh.setFormatter(new SimpleFormatter());
            fh.setLevel(Level.FINE);
            root.addHandler(fh);
            root.setLevel(Level.INFO);
        } catch (IOException ignored) {}
    }
}

// ---------------- JSON Utility (Refactor) ----------------
class JsonUtil {
    public static String stringify(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "\"" + esc((String)v) + "\"";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        if (v instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Object eObj : ((Map<?, ?>) v).entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) eObj;
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(esc(String.valueOf(e.getKey()))).append("\":").append(stringify(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(",");
                first = false;
                sb.append(stringify(o));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + esc(String.valueOf(v)) + "\"";
    }

    public static Object parse(String s) throws IOException {
        return new Parser(s).parse();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return (List<Object>) o; }

    public static void writeString(File file, String s) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String readString(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) sb.append(String.format("\\u%04x", (int)c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static class Parser {
        private final String s;
        private int i = 0;
        Parser(String s) { this.s = s; }
        Object parse() throws IOException {
            skip();
            Object v = readValue();
            skip();
            if (i != s.length()) throw err("Trailing content");
            return v;
        }
        private Object readValue() throws IOException {
            skip();
            if (i >= s.length()) throw err("Unexpected end");
            char c = s.charAt(i);
            if (c == '"') return readString();
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return readNumber();
        }
        private String readString() throws IOException {
            if (s.charAt(i) != '"') throw err("Expected string");
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw err("Bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw err("Bad unicode");
                            String hex = s.substring(i, i + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4; break;
                        default: throw err("Bad escape");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("Unterminated string");
        }
        private Map<String, Object> readObject() throws IOException {
            if (s.charAt(i) != '{') throw err("Expected {");
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            skip();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skip();
                String k = readString();
                skip();
                if (i >= s.length() || s.charAt(i) != ':') throw err("Expected :");
                i++;
                Object v = readValue();
                m.put(k, v);
                skip();
                if (i >= s.length()) throw err("Unterminated object");
                char c = s.charAt(i++);
                if (c == '}') break;
                if (c != ',') throw err("Expected ,");
            }
            return m;
        }
        private List<Object> readArray() throws IOException {
            if (s.charAt(i) != '[') throw err("Expected [");
            i++;
            List<Object> a = new ArrayList<>();
            skip();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                Object v = readValue();
                a.add(v);
                skip();
                if (i >= s.length()) throw err("Unterminated array");
                char c = s.charAt(i++);
                if (c == ']') break;
                if (c != ',') throw err("Expected ,");
            }
            return a;
        }
        private Number readNumber() throws IOException {
            int start = i;
            if (s.charAt(i) == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(start, i);
            try {
                if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) return Double.parseDouble(num);
                long l = Long.parseLong(num);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
                return l;
            } catch (NumberFormatException e) {
                throw err("Bad number");
            }
        }
        private void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private IOException err(String m) { return new IOException(m + " at " + i); }
    }
}

// ---------------- Core Models ----------------
class Task<T> implements Serializable {
    private static final long serialVersionUID = 4L;
    private String taskName;
    private String time;      // start time HH:MM
    private String endTime;   // end time HH:MM
    private T priority;
    private String category;
    private boolean completed;

    public Task(String taskName, String time, String endTime, T priority, String category) {
        this.taskName = taskName;
        this.time = time;
        this.endTime = endTime;
        this.priority = priority;
        this.category = category;
        this.completed = false;
    }

    // Backward-compatible constructor
    public Task(String taskName, String time, T priority, String category) {
        this(taskName, time, time, priority, category);
    }

    public String getTaskName() { return taskName; }
    public String getTime() { return time; }
    public String getEndTime() { return endTime; }
    public T getPriority() { return priority; }
    public String getCategory() { return category; }
    public boolean isCompleted() { return completed; }

    public void setTaskName(String taskName) { this.taskName = taskName; }
    public void setTime(String time) { this.time = time; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public void setPriority(T priority) { this.priority = priority; }
    public void setCategory(String category) { this.category = category; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    @Override
    public String toString() { return time + "–" + endTime + " - " + taskName + " [" + priority + "]"; }
}

class DaySchedule<T> implements Serializable {
    private static final long serialVersionUID = 1L;
    private String dayName;
    private List<Task<T>> tasks;
    public DaySchedule(String dayName) { this.dayName = dayName; this.tasks = new ArrayList<>(); }
    public void addTask(Task<T> task) { tasks.add(task); sortTasksByTime(); }
    public void removeTask(int index) { if (index >= 0 && index < tasks.size()) tasks.remove(index); }
    public List<Task<T>> getTasks() { return tasks; }
    public String getDayName() { return dayName; }
    private void sortTasksByTime() {
        Collections.sort(tasks, new Comparator<Task<T>>() { @Override public int compare(Task<T> t1, Task<T> t2) {
            int c = t1.getTime().compareTo(t2.getTime());
            if (c != 0) return c;
            return t1.getEndTime().compareTo(t2.getEndTime());
        }});
    }
}

// ---------------- Persistence (uses JsonUtil) ----------------
class DataPersistence {
    enum Format { JSON, XML, SERIALIZED }

    public void save(Map<String, DaySchedule<String>> data, File file, Format format) throws IOException {
        try {
            switch (format) {
                case JSON:
                    Log.PERSIST.info("Saving JSON to " + file);
                    JsonUtil.writeString(file, JsonUtil.stringify(serializeToJsonObject(data)));
                    break;
                case XML:
                    Log.PERSIST.info("Saving XML to " + file);
                    JsonUtil.writeString(file, toXml(data));
                    break;
                case SERIALIZED:
                    Log.PERSIST.info("Saving serialized to " + file);
                    try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
                        oos.writeObject(new LinkedHashMap<>(data));
                    }
                    break;
            }
        } catch (IOException ex) {
            Log.PERSIST.log(Level.SEVERE, "Save failed", ex);
            throw ex;
        }
    }

    public Map<String, DaySchedule<String>> load(File file, Format format) throws IOException {
        try {
            switch (format) {
                case JSON:
                    Log.PERSIST.info("Loading JSON from " + file);
                    Object root = JsonUtil.parse(JsonUtil.readString(file));
                    return deserializeFromJsonObject(JsonUtil.obj(root));
                case XML:
                    Log.PERSIST.info("Loading XML from " + file);
                    return fromXml(JsonUtil.readString(file));
                case SERIALIZED:
                    Log.PERSIST.info("Loading serialized from " + file);
                    try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                        @SuppressWarnings("unchecked")
                        Map<String, DaySchedule<String>> m = (Map<String, DaySchedule<String>>) ois.readObject();
                        return m;
                    } catch (ClassNotFoundException e) {
                        throw new IOException("Serialized data incompatible", e);
                    }
            }
        } catch (IOException ex) {
            Log.PERSIST.log(Level.SEVERE, "Load failed", ex);
            throw ex;
        }
        throw new IOException("Unsupported format");
    }

    // JSON model: { "days":[ { "day":"Monday", "tasks":[ {...}, ... ] }, ... ] }
    private Map<String, Object> serializeToJsonObject(Map<String, DaySchedule<String>> data) {
        List<Object> days = new ArrayList<>();
        for (Map.Entry<String, DaySchedule<String>> e : data.entrySet()) {
            List<Object> tasks = new ArrayList<>();
            for (Task<String> t : e.getValue().getTasks()) {
                Map<String, Object> to = new LinkedHashMap<>();
                to.put("time", t.getTime());
                to.put("endTime", t.getEndTime());
                to.put("taskName", t.getTaskName());
                to.put("priority", String.valueOf(t.getPriority()));
                to.put("category", t.getCategory());
                to.put("completed", t.isCompleted());
                tasks.add(to);
            }
            Map<String, Object> dayObj = new LinkedHashMap<>();
            dayObj.put("day", e.getKey());
            dayObj.put("tasks", tasks);
            days.add(dayObj);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("days", days);
        return root;
    }

    private Map<String, DaySchedule<String>> deserializeFromJsonObject(Map<String, Object> root) {
        Map<String, DaySchedule<String>> out = new LinkedHashMap<>();
        Object daysObj = root.get("days");
        if (!(daysObj instanceof List)) return out;
        for (Object d : JsonUtil.arr(daysObj)) {
            Map<String, Object> day = JsonUtil.obj(d);
            String dayName = String.valueOf(day.get("day"));
            DaySchedule<String> schedule = new DaySchedule<>(dayName);
            Object tasksObj = day.get("tasks");
            if (tasksObj instanceof List) {
                for (Object to : JsonUtil.arr(tasksObj)) {
                    Map<String, Object> m = JsonUtil.obj(to);
                    String time = String.valueOf(m.get("time"));
                    Object endObj = m.get("endTime");
                    String endTime = endObj == null ? time : String.valueOf(endObj);
                    String taskName = String.valueOf(m.get("taskName"));
                    String priority = String.valueOf(m.get("priority"));
                    String category = String.valueOf(m.get("category"));
                    boolean completed = Boolean.TRUE.equals(m.get("completed"));
                    if (time != null && taskName != null) {
                        Task<String> t = new Task<>(taskName, time, endTime, priority, category);
                        t.setCompleted(completed);
                        schedule.addTask(t);
                    }
                }
            }
            out.put(dayName, schedule);
        }
        return out;
    }

    // XML
    private static String escXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
    private String toXml(Map<String, DaySchedule<String>> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<weeklyRoutine>\n");
        for (Map.Entry<String, DaySchedule<String>> e : data.entrySet()) {
            sb.append("  <day name=\"").append(escXml(e.getKey())).append("\">\n");
            for (Task<String> t : e.getValue().getTasks()) {
                sb.append("    <task completed=\"").append(t.isCompleted()).append("\">\n");
                sb.append("      <time>").append(escXml(t.getTime())).append("</time>\n");
                sb.append("      <endTime>").append(escXml(t.getEndTime())).append("</endTime>\n");
                sb.append("      <name>").append(escXml(t.getTaskName())).append("</name>\n");
                sb.append("      <priority>").append(escXml(String.valueOf(t.getPriority()))).append("</priority>\n");
                sb.append("      <category>").append(escXml(t.getCategory())).append("</category>\n");
                sb.append("    </task>\n");
            }
            sb.append("  </day>\n");
        }
        sb.append("</weeklyRoutine>\n");
        return sb.toString();
    }
    private Map<String, DaySchedule<String>> fromXml(String xml) {
        Map<String, DaySchedule<String>> map = new LinkedHashMap<>();
        int pos = 0;
        while (true) {
            int dayStart = xml.indexOf("<day", pos); if (dayStart < 0) break;
            int nameAttr = xml.indexOf("name=\"", dayStart); int nameEnd = nameAttr >= 0 ? xml.indexOf("\"", nameAttr + 6) : -1;
            if (nameAttr < 0 || nameEnd < 0) break;
            String dayName = xml.substring(nameAttr + 6, nameEnd);
            int closeDayStart = xml.indexOf(">", nameEnd); int dayEnd = xml.indexOf("</day>", closeDayStart);
            if (closeDayStart < 0 || dayEnd < 0) break;
            String body = xml.substring(closeDayStart + 1, dayEnd);
            DaySchedule<String> schedule = new DaySchedule<>(dayName);
            int tpos = 0;
            while (true) {
                int ts = body.indexOf("<task", tpos); if (ts < 0) break;
                int te = body.indexOf("</task>", ts); if (te < 0) break;
                String taskTag = body.substring(ts, te);
                boolean completed = taskTag.contains("completed=\"true\"");
                int gt = taskTag.indexOf(">");
                String txml = body.substring(ts + gt + 1, te);
                String time = extractTag(txml, "time");
                String endTime = extractTag(txml, "endTime");
                String name = extractTag(txml, "name");
                String priority = extractTag(txml, "priority");
                String category = extractTag(txml, "category");
                if (time != null && name != null) {
                    if (endTime == null) endTime = time;
                    Task<String> t = new Task<>(name, time, endTime, priority != null ? priority : "Low", category != null ? category : "Other");
                    t.setCompleted(completed); schedule.addTask(t);
                }
                tpos = te + 7;
            }
            map.put(dayName, schedule); pos = dayEnd + 6;
        }
        return map;
    }
    private static String extractTag(String xml, String tag) {
        int s = xml.indexOf("<" + tag + ">"); if (s < 0) return null;
        int e = xml.indexOf("</" + tag + ">", s); if (e < 0) return null;
        return xml.substring(s + tag.length() + 2, e).replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&apos;","'").replace("&amp;","&");
    }

    public static DataPersistence.Format formatFromFile(File f) {
        String name = f.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) return Format.JSON;
        if (name.endsWith(".xml")) return Format.XML;
        if (name.endsWith(".ser")) return Format.SERIALIZED;
        return Format.JSON;
    }
}

// ---------------- Recurrence & Templates ----------------
class RecurringTasks {
    public enum Recurrence { NONE, DAILY, WEEKLY, WEEKDAYS }
    public static void applyRecurrence(Map<String, DaySchedule<String>> weekSchedule, String[] daysOfWeek, String currentDay, Task<String> task, Recurrence recurrence) {
        switch (recurrence) {
            case NONE: case WEEKLY: weekSchedule.get(currentDay).addTask(task); break;
            case DAILY: for (String d : daysOfWeek) weekSchedule.get(d).addTask(cloneTask(task)); break;
            case WEEKDAYS: for (String d : daysOfWeek) if (!d.equalsIgnoreCase("Saturday") && !d.equalsIgnoreCase("Sunday")) weekSchedule.get(d).addTask(cloneTask(task)); break;
        }
    }
    public static void applyToDays(Map<String, DaySchedule<String>> weekSchedule, Collection<String> targetDays, Task<String> task) {
        for (String d : targetDays) { DaySchedule<String> s = weekSchedule.get(d); if (s != null) s.addTask(cloneTask(task)); }
    }
    private static Task<String> cloneTask(Task<String> t) {
        Task<String> c = new Task<>(t.getTaskName(), t.getTime(), t.getEndTime(), t.getPriority(), t.getCategory());
        c.setCompleted(t.isCompleted());
        return c;
    }

    static class TemplateManager {
        private final Map<String, List<Task<String>>> dayTemplates = new LinkedHashMap<>();
        private final File templatesFile = new File(System.getProperty("user.home"), "weekly_routine_templates.json");
        TemplateManager() { load(); }
        Set<String> getTemplateNames() { return dayTemplates.keySet(); }
        void saveDayTemplate(String name, List<Task<String>> tasks) {
            List<Task<String>> copy = new ArrayList<>(); for (Task<String> t : tasks) copy.add(cloneTask(t));
            dayTemplates.put(name, copy); persist();
        }
        void deleteTemplate(String name) { dayTemplates.remove(name); persist(); }
        List<Task<String>> getTemplate(String name) {
            List<Task<String>> src = dayTemplates.get(name); if (src == null) return null;
            List<Task<String>> copy = new ArrayList<>(); for (Task<String> t : src) copy.add(cloneTask(t)); return copy;
        }
        private void load() {
            if (!templatesFile.exists()) return;
            try {
                Object root = JsonUtil.parse(JsonUtil.readString(templatesFile));
                Map<String,Object> obj = JsonUtil.obj(root);
                List<Object> arr = JsonUtil.arr(obj.get("templates"));
                dayTemplates.clear();
                for (Object o : arr) {
                    Map<String,Object> to = JsonUtil.obj(o);
                    String name = String.valueOf(to.get("name"));
                    List<Task<String>> tasks = new ArrayList<>();
                    for (Object tt : JsonUtil.arr(to.get("tasks"))) {
                        Map<String,Object> tm = JsonUtil.obj(tt);
                        String time = String.valueOf(tm.get("time"));
                        Object endObj = tm.get("endTime");
                        String end = endObj == null ? time : String.valueOf(endObj);
                        Task<String> t = new Task<>(String.valueOf(tm.get("taskName")), time, end,
                                String.valueOf(tm.get("priority")), String.valueOf(tm.get("category")));
                        t.setCompleted(Boolean.TRUE.equals(tm.get("completed"))); tasks.add(t);
                    }
                    dayTemplates.put(name, tasks);
                }
            } catch (Exception ex) { Log.PERSIST.log(Level.WARNING, "Template load failed", ex); }
        }
        private void persist() {
            try {
                List<Object> arr = new ArrayList<>();
                for (Map.Entry<String,List<Task<String>>> e : dayTemplates.entrySet()) {
                    Map<String,Object> to = new LinkedHashMap<>();
                    to.put("name", e.getKey());
                    List<Object> tl = new ArrayList<>();
                    for (Task<String> t : e.getValue()) {
                        Map<String,Object> tm = new LinkedHashMap<>();
                        tm.put("time", t.getTime());
                        tm.put("endTime", t.getEndTime());
                        tm.put("taskName", t.getTaskName());
                        tm.put("priority", String.valueOf(t.getPriority()));
                        tm.put("category", t.getCategory());
                        tm.put("completed", t.isCompleted());
                        tl.add(tm);
                    }
                    to.put("tasks", tl); arr.add(to);
                }
                Map<String,Object> root = new LinkedHashMap<>(); root.put("templates", arr);
                JsonUtil.writeString(templatesFile, JsonUtil.stringify(root));
            } catch (IOException ex) { Log.PERSIST.log(Level.WARNING, "Template save failed", ex); }
        }
    }
}

// ---------------- Tracking ----------------
class TaskCompletionTracking {
    private final File historyFile = new File(System.getProperty("user.home"), "weekly_routine_history.json");

    public void recordToggle(String uiDayName, String taskName, boolean completed, LocalDate date) {
        try {
            Map<String,Object> root = ensureRoot();
            Map<String,Object> daysMap = ensureMap(root, "days");
            String dateKey = date.toString();
            Map<String,Object> dayEntry = ensureMap(daysMap, dateKey);
            Map<String,Object> dayTasks = ensureMap(dayEntry, uiDayName);
            dayTasks.put(taskName, completed);
            JsonUtil.writeString(historyFile, JsonUtil.stringify(root));
        } catch (IOException ex) { Log.TRACK.log(Level.WARNING, "recordToggle failed", ex); }
    }
    public int dailyCompletionPercent(DaySchedule<String> schedule) {
        List<Task<String>> tasks = schedule.getTasks(); if (tasks.isEmpty()) return 0;
        int done = 0; for (Task<String> t : tasks) if (t.isCompleted()) done++; return (int)Math.round(100.0*done/tasks.size());
    }
    public Stats computeStats() {
        try {
            Map<String,Object> root = ensureRoot();
            @SuppressWarnings("unchecked")
            Map<String,Object> days = (Map<String,Object>) root.get("days");
            LocalDate today = LocalDate.now();
            LocalDate weekStart = today.minusDays((today.getDayOfWeek().getValue() + 6) % 7);
            YearMonth ym = YearMonth.from(today); LocalDate monthStart = ym.atDay(1); LocalDate monthEnd = ym.atEndOfMonth();
            int wc=0, wt=0, mc=0, mt=0;
            for (Map.Entry<String,Object> e : days.entrySet()) {
                LocalDate d = LocalDate.parse(e.getKey());
                boolean inWeek = !d.isBefore(weekStart) && !d.isAfter(weekStart.plusDays(6));
                boolean inMonth = !d.isBefore(monthStart) && !d.isAfter(monthEnd);
                if (!inWeek && !inMonth) continue;
                @SuppressWarnings("unchecked")
                Map<String,Object> perDay = (Map<String,Object>) e.getValue();
                for (Object v : perDay.values()) {
                    @SuppressWarnings("unchecked")
                    Map<String,Object> tasks = (Map<String,Object>) v;
                    int total = tasks.size(); int done = 0;
                    for (Object bv : tasks.values()) if (Boolean.TRUE.equals(bv)) done++;
                    if (inWeek) { wc += done; wt += total; }
                    if (inMonth) { mc += done; mt += total; }
                }
            }
            return new Stats(wc, wt, mc, mt, computeStreak(days));
        } catch (Exception ex) {
            Log.TRACK.log(Level.WARNING, "computeStats failed", ex);
            return new Stats(0,0,0,0,0);
        }
    }
    private int computeStreak(Map<String,Object> days) {
        int streak=0; LocalDate d = LocalDate.now();
        while (true) {
            Object entry = days.get(d.toString()); if (!(entry instanceof Map)) break;
            @SuppressWarnings("unchecked")
            Map<String,Object> perDay = (Map<String,Object>) entry;
            int total=0, done=0;
            for (Object v : perDay.values()) {
                @SuppressWarnings("unchecked")
                Map<String,Object> tasks = (Map<String,Object>) v;
                total += tasks.size();
                for (Object bv : tasks.values()) if (Boolean.TRUE.equals(bv)) done++;
            }
            if (total==0) break;
            int pct = (int)Math.round(100.0*done/total);
            if (pct >= 80) { streak++; d = d.minusDays(1); } else break;
        }
        return streak;
    }
    public static class Stats {
        public final int weekCompleted, weekTotal, monthCompleted, monthTotal, streakDays;
        public Stats(int wc,int wt,int mc,int mt,int s){ weekCompleted=wc; weekTotal=wt; monthCompleted=mc; monthTotal=mt; streakDays=s; }
    }
    private Map<String,Object> ensureRoot() throws IOException {
        if (!historyFile.exists()) {
            Map<String,Object> r = new LinkedHashMap<>(); r.put("days", new LinkedHashMap<String,Object>());
            JsonUtil.writeString(historyFile, JsonUtil.stringify(r)); return r;
        }
        Object root = JsonUtil.parse(JsonUtil.readString(historyFile)); return JsonUtil.obj(root);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> ensureMap(Map<String,Object> parent, String key) { return (Map<String,Object>) parent.computeIfAbsent(key, k -> new LinkedHashMap<String,Object>()); }
}

// ---------------- UI ----------------
public class Base extends JFrame {
    private Map<String, DaySchedule<String>> weekSchedule;
    private String[] daysOfWeek = {"Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"};
    private String currentDay;
    private JComboBox<String> daySelector;
    private JTable taskTable;
    private DefaultTableModel tableModel;
    private JTextField taskNameField;
    private JTextField timeField;
    private JTextField endTimeField;
    private JComboBox<String> priorityCombo;
    private JComboBox<String> categoryCombo;
    private JComboBox<String> recurrenceCombo;
    private JButton addButton, addMultiButton, editButton, deleteButton, clearDayButton, copyDayButton;
    private JTextArea summaryArea;
    private JProgressBar dailyProgressBar;

    private final DataPersistence persistence = new DataPersistence();
    private final File autoSaveFile = new File(System.getProperty("user.home"), "weekly_routine_autosave.json");
    private javax.swing.Timer autoSaveTimer;
    private boolean autoSaveEnabled = true;

    private final RecurringTasks.TemplateManager templateManager = new RecurringTasks.TemplateManager();
    private final TaskCompletionTracking tracking = new TaskCompletionTracking();
    
    public Base() {
        weekSchedule = new LinkedHashMap<>(); for (String d : daysOfWeek) weekSchedule.put(d, new DaySchedule<>(d));
        currentDay = daysOfWeek[0];
        setTitle("Weekly Routine Manager");
        setSize(1080, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        setJMenuBar(createMenuBar());
        createTopPanel(); createCenterPanel(); createBottomPanel();
        if (autoSaveFile.exists()) {
            try { weekSchedule = persistence.load(autoSaveFile, DataPersistence.Format.JSON); }
            catch (IOException ex) { Log.PERSIST.log(Level.WARNING, "Autosave load failed", ex); }
        }
        loadDaySchedule(currentDay); updateDailyProgress();
        setupAutoSave();
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) {
            if (autoSaveEnabled) try { persistence.save(weekSchedule, autoSaveFile, DataPersistence.Format.JSON);} catch (IOException ex){ Log.PERSIST.log(Level.WARNING,"Autosave on close failed",ex); }
        }});
        setLocationRelativeTo(null); setVisible(true);
    }

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");

        JMenuItem newRoutine = new JMenuItem("New Routine...");
        newRoutine.addActionListener(e -> onNewRoutine());
        JMenuItem newFromTemplate = new JMenuItem("New From Template...");
        newFromTemplate.addActionListener(e -> onNewRoutineFromTemplate());
        file.add(newRoutine);
        file.add(newFromTemplate);
        file.addSeparator();

        JMenuItem save = new JMenuItem("Save..."); save.addActionListener(e -> onSave());
        JMenuItem load = new JMenuItem("Load..."); load.addActionListener(e -> onLoad());
        JMenu exportMenu = new JMenu("Export");
        JMenuItem exportJson = new JMenuItem("Export as JSON..."); exportJson.addActionListener(e -> onExport(DataPersistence.Format.JSON));
        JMenuItem exportXml = new JMenuItem("Export as XML..."); exportXml.addActionListener(e -> onExport(DataPersistence.Format.XML));
        JMenuItem exportSer = new JMenuItem("Export as Serialized..."); exportSer.addActionListener(e -> onExport(DataPersistence.Format.SERIALIZED));
        exportMenu.add(exportJson); exportMenu.add(exportXml); exportMenu.add(exportSer);
        JMenu importMenu = new JMenu("Import");
        JMenuItem importFile = new JMenuItem("Import from file..."); importFile.addActionListener(e -> onImport());
        importMenu.add(importFile);
        JCheckBoxMenuItem autoSave = new JCheckBoxMenuItem("Auto-save (every 60s)");
        autoSave.setSelected(autoSaveEnabled);
        autoSave.addActionListener(e -> { autoSaveEnabled = ((JCheckBoxMenuItem)e.getSource()).isSelected(); if (autoSaveEnabled) startAutoSave(); else stopAutoSave(); });
        JMenu templates = new JMenu("Templates");
        JMenuItem saveDayTpl = new JMenuItem("Save Current Day as Template..."); saveDayTpl.addActionListener(e -> onSaveDayTemplate());
        JMenuItem applyTpl = new JMenuItem("Apply Template to Days..."); applyTpl.addActionListener(e -> onApplyTemplateToDays());
        JMenuItem manageTpl = new JMenuItem("Delete Template..."); manageTpl.addActionListener(e -> onDeleteTemplate());
        JMenu stats = new JMenu("Stats");
        JMenuItem showStats = new JMenuItem("Show Weekly/Monthly Stats"); showStats.addActionListener(e -> onShowStats());
        templates.add(saveDayTpl); templates.add(applyTpl); templates.add(manageTpl);
        file.add(save); file.add(load); file.addSeparator(); file.add(exportMenu); file.add(importMenu); file.addSeparator(); file.add(autoSave);
        bar.add(file); bar.add(templates); bar.add(stats); return bar;
    }

    private void setupAutoSave() {
        autoSaveTimer = new javax.swing.Timer(60_000, e -> {
            if (!autoSaveEnabled) return;
            try { persistence.save(weekSchedule, autoSaveFile, DataPersistence.Format.JSON); }
            catch (IOException ex) { Log.PERSIST.log(Level.WARNING, "Autosave failed", ex); }
        });
        if (autoSaveEnabled) autoSaveTimer.start();
    }
    private void startAutoSave() { if (autoSaveTimer != null && !autoSaveTimer.isRunning()) autoSaveTimer.start(); }
    private void stopAutoSave() { if (autoSaveTimer != null && autoSaveTimer.isRunning()) autoSaveTimer.stop(); }
    
    private void createTopPanel() {
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));
        topPanel.setBackground(new Color(70,130,180));
        JLabel titleLabel = new JLabel("Weekly Routine Manager");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 20)); titleLabel.setForeground(Color.WHITE);
        JLabel dayLabel = new JLabel("Select Day:"); dayLabel.setForeground(Color.WHITE); dayLabel.setFont(new Font("Arial", Font.PLAIN, 14));
        daySelector = new JComboBox<>(daysOfWeek); daySelector.setPreferredSize(new Dimension(150, 30));
        daySelector.addActionListener(e -> { currentDay = (String) daySelector.getSelectedItem(); loadDaySchedule(currentDay); updateDailyProgress(); });
        topPanel.add(titleLabel); topPanel.add(Box.createHorizontalStrut(50)); topPanel.add(dayLabel); topPanel.add(daySelector);
        add(topPanel, BorderLayout.NORTH);
    }
    
    private void createCenterPanel() {
        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        JPanel inputPanel = new JPanel(new GridLayout(7, 2, 10, 10));
        inputPanel.setBorder(BorderFactory.createTitledBorder("Add New Task"));
        inputPanel.add(new JLabel("Task Name:")); taskNameField = new JTextField(); inputPanel.add(taskNameField);
        inputPanel.add(new JLabel("Start Time (HH:MM):")); timeField = new JTextField(); inputPanel.add(timeField);
        inputPanel.add(new JLabel("End Time (HH:MM):")); endTimeField = new JTextField(); inputPanel.add(endTimeField);
        inputPanel.add(new JLabel("Priority:")); priorityCombo = new JComboBox<>(new String[]{"High","Medium","Low"}); inputPanel.add(priorityCombo);
        inputPanel.add(new JLabel("Category:")); categoryCombo = new JComboBox<>(new String[]{"Work","Exercise","Personal","Study","Health","Other"}); inputPanel.add(categoryCombo);
        inputPanel.add(new JLabel("Recurrence:")); recurrenceCombo = new JComboBox<>(new String[]{"None","Daily","Weekly","Weekdays"}); inputPanel.add(recurrenceCombo);
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        addButton = new JButton("Add Task"); addButton.setBackground(new Color(46,204,113)); addButton.setForeground(Color.WHITE);
        addMultiButton = new JButton("Add to Multiple Days"); addMultiButton.setBackground(new Color(39,174,96)); addMultiButton.setForeground(Color.WHITE);
        editButton = new JButton("Edit Selected"); editButton.setBackground(new Color(241, 196, 15)); editButton.setForeground(Color.WHITE);
        deleteButton = new JButton("Delete Selected"); deleteButton.setBackground(new Color(231,76,60)); deleteButton.setForeground(Color.WHITE);
        clearDayButton = new JButton("Clear Day"); clearDayButton.setBackground(new Color(230,126,34)); clearDayButton.setForeground(Color.WHITE);
        copyDayButton = new JButton("Copy to..."); copyDayButton.setBackground(new Color(52,152,219)); copyDayButton.setForeground(Color.WHITE);
        addButton.addActionListener(e -> addTaskWithRecurrence());
        addMultiButton.addActionListener(e -> addTaskToMultipleDays());
        editButton.addActionListener(e -> editSelectedTask());
        deleteButton.addActionListener(e -> deleteSelectedTask());
        clearDayButton.addActionListener(e -> clearCurrentDay());
        copyDayButton.addActionListener(e -> copyDaySchedule());
        buttonPanel.add(addButton); buttonPanel.add(addMultiButton); buttonPanel.add(editButton); buttonPanel.add(deleteButton); buttonPanel.add(clearDayButton); buttonPanel.add(copyDayButton);
        inputPanel.add(buttonPanel);

        String[] columnNames = {"Done","Start","End","Task Name","Priority","Category"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override public Class<?> getColumnClass(int columnIndex) { return columnIndex==0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        };
        taskTable = new JTable(tableModel); taskTable.setRowHeight(25); taskTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(taskTable); scrollPane.setBorder(BorderFactory.createTitledBorder("Daily Tasks"));
        tableModel.addTableModelListener(e -> {
            if (e.getType()==TableModelEvent.UPDATE && e.getColumn()==0 && e.getFirstRow()>=0) {
                int row = e.getFirstRow(); DaySchedule<String> schedule = weekSchedule.get(currentDay);
                if (row < schedule.getTasks().size()) {
                    Task<String> t = schedule.getTasks().get(row);
                    boolean completed = Boolean.TRUE.equals(tableModel.getValueAt(row, 0));
                    t.setCompleted(completed); tracking.recordToggle(currentDay, t.getTaskName(), completed, LocalDate.now());
                    updateDailyProgress();
                }
            }
        });

        centerPanel.add(inputPanel, BorderLayout.NORTH);
        centerPanel.add(scrollPane, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);
    }
    
    private void createBottomPanel() {
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        summaryArea = new JTextArea(6, 50); summaryArea.setEditable(false); summaryArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane summaryScroll = new JScrollPane(summaryArea); summaryScroll.setBorder(BorderFactory.createTitledBorder("Week Summary"));
        JPanel south = new JPanel(new BorderLayout(10, 10));
        dailyProgressBar = new JProgressBar(0, 100); dailyProgressBar.setStringPainted(true); dailyProgressBar.setValue(0);
        dailyProgressBar.setBorder(BorderFactory.createTitledBorder("Today's Completion")); south.add(dailyProgressBar, BorderLayout.CENTER);
        JButton showSummaryButton = new JButton("Show Weekly Summary"); showSummaryButton.setBackground(new Color(155, 89, 182)); showSummaryButton.setForeground(Color.WHITE);
        showSummaryButton.addActionListener(e -> showWeeklySummary());
        south.add(showSummaryButton, BorderLayout.EAST);
        bottomPanel.add(summaryScroll, BorderLayout.CENTER); bottomPanel.add(south, BorderLayout.SOUTH);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void updateDailyProgress() {
        DaySchedule<String> schedule = weekSchedule.get(currentDay);
        int pct = tracking.dailyCompletionPercent(schedule);
        dailyProgressBar.setValue(pct); dailyProgressBar.setString(pct + "%");
    }

    private boolean isValidHHMM(String s) {
        return s != null && s.matches("\\d{2}:\\d{2}");
    }
    private boolean isEndAfterOrEqual(String start, String end) {
        return end.compareTo(start) >= 0;
    }
    
    private void addTaskWithRecurrence() {
        try {
            String taskName = taskNameField.getText().trim();
            String start = timeField.getText().trim();
            String end = endTimeField.getText().trim();
            String priority = (String) priorityCombo.getSelectedItem();
            String category = (String) categoryCombo.getSelectedItem();
            String recurrenceStr = (String) recurrenceCombo.getSelectedItem();
            if (taskName.isEmpty() || start.isEmpty() || end.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Task name, start, and end time are required!", "Input Error", JOptionPane.ERROR_MESSAGE); return;
            }
            if (!isValidHHMM(start) || !isValidHHMM(end)) {
                JOptionPane.showMessageDialog(this, "Times must be in HH:MM format!", "Input Error", JOptionPane.ERROR_MESSAGE); return;
            }
            if (!isEndAfterOrEqual(start, end)) {
                JOptionPane.showMessageDialog(this, "End time must be after or equal to start time!", "Input Error", JOptionPane.ERROR_MESSAGE); return;
            }
            Task<String> task = new Task<>(taskName, start, end, priority, category);
            RecurringTasks.Recurrence rec = parseRecurrence(recurrenceStr);
            RecurringTasks.applyRecurrence(weekSchedule, daysOfWeek, currentDay, task, rec);
            taskNameField.setText(""); timeField.setText(""); endTimeField.setText("");
            loadDaySchedule(currentDay); updateDailyProgress();
            JOptionPane.showMessageDialog(this, "Task added" + (rec != RecurringTasks.Recurrence.NONE ? " with recurrence." : "!"), "Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            Log.UI.log(Level.WARNING, "Add task failed", ex);
            JOptionPane.showMessageDialog(this, "Failed to add task: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void addTaskToMultipleDays() {
        try {
            String taskName = taskNameField.getText().trim();
            String start = timeField.getText().trim();
            String end = endTimeField.getText().trim();
            String priority = (String) priorityCombo.getSelectedItem();
            String category = (String) categoryCombo.getSelectedItem();
            if (taskName.isEmpty() || start.isEmpty() || end.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Task name, start, and end time are required!", "Input Error", JOptionPane.ERROR_MESSAGE); return;
            }
            if (!isValidHHMM(start) || !isValidHHMM(end) || !isEndAfterOrEqual(start, end)) {
                JOptionPane.showMessageDialog(this, "Invalid start/end time.", "Input Error", JOptionPane.ERROR_MESSAGE); return;
            }
            List<String> selectedDays = showDaysSelectionDialog("Apply task to which days?"); if (selectedDays == null || selectedDays.isEmpty()) return;
            Task<String> t = new Task<>(taskName, start, end, priority, category);
            RecurringTasks.applyToDays(weekSchedule, selectedDays, t);
            loadDaySchedule(currentDay); updateDailyProgress();
            JOptionPane.showMessageDialog(this, "Task applied to selected days.", "Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            Log.UI.log(Level.WARNING, "Add to multiple failed", ex);
            JOptionPane.showMessageDialog(this, "Failed to apply task: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void editSelectedTask() {
        int row = taskTable.getSelectedRow();
        if (row == -1) { JOptionPane.showMessageDialog(this, "Select a task to edit.", "Info", JOptionPane.INFORMATION_MESSAGE); return; }
        DaySchedule<String> schedule = weekSchedule.get(currentDay);
        if (row >= schedule.getTasks().size()) return;
        Task<String> t = schedule.getTasks().get(row);

        JTextField nameF = new JTextField(t.getTaskName());
        JTextField startF = new JTextField(t.getTime());
        JTextField endF = new JTextField(t.getEndTime());
        JComboBox<String> prioF = new JComboBox<>(new String[]{"High","Medium","Low"}); prioF.setSelectedItem(String.valueOf(t.getPriority()));
        JComboBox<String> catF = new JComboBox<>(new String[]{"Work","Exercise","Personal","Study","Health","Other"}); catF.setSelectedItem(t.getCategory());
        JPanel panel = new JPanel(new GridLayout(0,2,8,8));
        panel.add(new JLabel("Task Name:")); panel.add(nameF);
        panel.add(new JLabel("Start Time (HH:MM):")); panel.add(startF);
        panel.add(new JLabel("End Time (HH:MM):")); panel.add(endF);
        panel.add(new JLabel("Priority:")); panel.add(prioF);
        panel.add(new JLabel("Category:")); panel.add(catF);
        int res = JOptionPane.showConfirmDialog(this, panel, "Edit Task", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            String newName = nameF.getText().trim();
            String newStart = startF.getText().trim();
            String newEnd = endF.getText().trim();
            if (newName.isEmpty() || !isValidHHMM(newStart) || !isValidHHMM(newEnd) || !isEndAfterOrEqual(newStart, newEnd)) {
                JOptionPane.showMessageDialog(this, "Invalid input.", "Error", JOptionPane.ERROR_MESSAGE); return;
            }
            t.setTaskName(newName); t.setTime(newStart); t.setEndTime(newEnd);
            t.setPriority((String) prioF.getSelectedItem()); t.setCategory((String) catF.getSelectedItem());
            schedule.getTasks().sort((a,b) -> {
                int c = a.getTime().compareTo(b.getTime());
                if (c != 0) return c;
                return a.getEndTime().compareTo(b.getEndTime());
            });
            loadDaySchedule(currentDay); updateDailyProgress();
        }
    }

    private RecurringTasks.Recurrence parseRecurrence(String s) {
        if (s == null) return RecurringTasks.Recurrence.NONE;
        switch (s) { case "Daily": return RecurringTasks.Recurrence.DAILY; case "Weekly": return RecurringTasks.Recurrence.WEEKLY; case "Weekdays": return RecurringTasks.Recurrence.WEEKDAYS; default: return RecurringTasks.Recurrence.NONE; }
    }
    
    private void deleteSelectedTask() {
        int selectedRow = taskTable.getSelectedRow();
        if (selectedRow == -1) { JOptionPane.showMessageDialog(this, "Please select a task to delete!", "Selection Error", JOptionPane.WARNING_MESSAGE); return; }
        try {
            weekSchedule.get(currentDay).removeTask(selectedRow);
            loadDaySchedule(currentDay); updateDailyProgress();
            JOptionPane.showMessageDialog(this, "Task deleted successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            Log.UI.log(Level.WARNING, "Delete failed", ex);
            JOptionPane.showMessageDialog(this, "Failed to delete task: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void clearCurrentDay() {
        int confirm = JOptionPane.showConfirmDialog(this, "Are you sure you want to clear all tasks for " + currentDay + "?", "Confirm Clear", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            weekSchedule.put(currentDay, new DaySchedule<>(currentDay));
            loadDaySchedule(currentDay); updateDailyProgress();
            JOptionPane.showMessageDialog(this, "Day cleared successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
        }
    }
    
    private void copyDaySchedule() {
        String targetDay = (String) JOptionPane.showInputDialog(this, "Copy " + currentDay + "'s schedule to:", "Copy Schedule", JOptionPane.QUESTION_MESSAGE, null, daysOfWeek, daysOfWeek[0]);
        if (targetDay != null && !targetDay.equals(currentDay)) {
            try {
                DaySchedule<String> sourceSchedule = weekSchedule.get(currentDay);
                DaySchedule<String> targetSchedule = new DaySchedule<>(targetDay);
                for (Task<String> task : sourceSchedule.getTasks()) {
                    Task<String> newTask = new Task<>(task.getTaskName(), task.getTime(), task.getEndTime(), task.getPriority(), task.getCategory());
                    newTask.setCompleted(task.isCompleted()); targetSchedule.addTask(newTask);
                }
                weekSchedule.put(targetDay, targetSchedule);
                JOptionPane.showMessageDialog(this, "Schedule copied to " + targetDay + " successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                Log.UI.log(Level.WARNING, "Copy failed", ex);
                JOptionPane.showMessageDialog(this, "Failed to copy: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void onNewRoutine() {
        int res = JOptionPane.showConfirmDialog(
                this,
                "Start a new routine? This will clear all days.\n\nDo you want to save the current routine first?",
                "New Routine",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (res == JOptionPane.CANCEL_OPTION) return;
        if (res == JOptionPane.YES_OPTION) {
            onSave();
        }

        Map<String, DaySchedule<String>> fresh = new LinkedHashMap<>();
        for (String d : daysOfWeek) fresh.put(d, new DaySchedule<>(d));
        weekSchedule = fresh;
        currentDay = daysOfWeek[0];
        daySelector.setSelectedItem(currentDay);
        loadDaySchedule(currentDay);
        updateDailyProgress();

        try {
            if (autoSaveFile.exists()) autoSaveFile.delete();
        } catch (Exception ignore) {}

        JOptionPane.showMessageDialog(this, "New routine created.", "New Routine", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onNewRoutineFromTemplate() {
        if (templateManager.getTemplateNames().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No templates available.", "Templates", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String name = (String) JOptionPane.showInputDialog(
                this,
                "Select a template to start from:",
                "New From Template",
                JOptionPane.QUESTION_MESSAGE,
                null,
                templateManager.getTemplateNames().toArray(),
                null
        );
        if (name == null) return;

        List<Task<String>> templateTasks = templateManager.getTemplate(name);
        if (templateTasks == null || templateTasks.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Selected template is empty.", "Templates", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int res = JOptionPane.showConfirmDialog(
                this,
                "This will clear all days. Continue?",
                "Confirm",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (res != JOptionPane.OK_OPTION) return;

        Map<String, DaySchedule<String>> fresh = new LinkedHashMap<>();
        for (String d : daysOfWeek) fresh.put(d, new DaySchedule<>(d));
        weekSchedule = fresh;

        List<String> days = showDaysSelectionDialog("Apply template to which days?");
        if (days != null && !days.isEmpty()) {
            for (String d : days) {
                DaySchedule<String> s = weekSchedule.get(d);
                for (Task<String> t : templateTasks) {
                    s.addTask(new Task<>(t.getTaskName(), t.getTime(), t.getEndTime(), t.getPriority(), t.getCategory()));
                }
            }
        }

        currentDay = daysOfWeek[0];
        daySelector.setSelectedItem(currentDay);
        loadDaySchedule(currentDay);
        updateDailyProgress();

        JOptionPane.showMessageDialog(this, "New routine created from template: " + name, "New From Template", JOptionPane.INFORMATION_MESSAGE);
    }

    private void loadDaySchedule(String day) {
        tableModel.setRowCount(0);
        DaySchedule<String> schedule = weekSchedule.get(day);
        for (Task<String> task : schedule.getTasks()) {
            tableModel.addRow(new Object[]{
                    task.isCompleted(),
                    task.getTime(),
                    task.getEndTime(),
                    task.getTaskName(),
                    String.valueOf(task.getPriority()),
                    task.getCategory()
            });
        }
    }
    
    private void showWeeklySummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("WEEKLY ROUTINE SUMMARY\n").append("=".repeat(60)).append("\n\n");
        for (String day : daysOfWeek) {
            DaySchedule<String> schedule = weekSchedule.get(day);
            summary.append(day).append(" (").append(schedule.getTasks().size()).append(" tasks):\n");
            if (schedule.getTasks().isEmpty()) {
                summary.append("  No tasks scheduled\n");
            } else {
                for (Task<String> task : schedule.getTasks()) {
                    summary.append("  [").append(task.isCompleted() ? "x":" ").append("] ")
                           .append(task.getTime()).append("–").append(task.getEndTime()).append(" - ")
                           .append(task.getTaskName()).append(" [").append(task.getPriority()).append("]")
                           .append(" - ").append(task.getCategory()).append("\n");
                }
            }
            summary.append("\n");
        }
        summaryArea.setText(summary.toString());
    }

    private void onShowStats() {
        TaskCompletionTracking.Stats s = tracking.computeStats();
        StringBuilder b = new StringBuilder();
        b.append("Stats\n").append("=".repeat(40)).append("\n");
        b.append("This week: ").append(s.weekCompleted).append(" / ").append(s.weekTotal).append(" completed");
        if (s.weekTotal > 0) b.append(" (").append((int)Math.round(100.0*s.weekCompleted/s.weekTotal)).append("%)");
        b.append("\n");
        b.append("This month: ").append(s.monthCompleted).append(" / ").append(s.monthTotal).append(" completed");
        if (s.monthTotal > 0) b.append(" (").append((int)Math.round(100.0*s.monthCompleted/s.monthTotal)).append("%)");
        b.append("\n");
        b.append("Streak (>=80% days): ").append(s.streakDays).append(" day(s)\n");
        JOptionPane.showMessageDialog(this, b.toString(), "Statistics", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onSave() {
        JFileChooser fc = new JFileChooser(); fc.setDialogTitle("Save Routine"); fc.setSelectedFile(new File("routine.json"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile(); DataPersistence.Format fmt = DataPersistence.formatFromFile(f);
            try { persistence.save(weekSchedule, f, fmt); JOptionPane.showMessageDialog(this, "Saved to: " + f.getAbsolutePath(), "Saved", JOptionPane.INFORMATION_MESSAGE); }
            catch (IOException ex) { Log.PERSIST.log(Level.SEVERE, "Save failed", ex); JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE); }
        }
    }

    private void onLoad() {
        JFileChooser fc = new JFileChooser(); fc.setDialogTitle("Load Routine");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile(); DataPersistence.Format fmt = DataPersistence.formatFromFile(f);
            try {
                Map<String, DaySchedule<String>> loaded = persistence.load(f, fmt);
                Map<String, DaySchedule<String>> ordered = new LinkedHashMap<>();
                for (String d : daysOfWeek) ordered.put(d, loaded.getOrDefault(d, new DaySchedule<>(d)));
                weekSchedule = ordered; loadDaySchedule(currentDay); updateDailyProgress();
                JOptionPane.showMessageDialog(this, "Loaded from: " + f.getAbsolutePath(), "Loaded", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) { Log.PERSIST.log(Level.SEVERE, "Load failed", ex); JOptionPane.showMessageDialog(this, "Load failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE); }
        }
    }

    private void onExport(DataPersistence.Format fmt) {
        JFileChooser fc = new JFileChooser(); fc.setDialogTitle("Export Routine (" + fmt + ")");
        String def = "routine" + (fmt==DataPersistence.Format.JSON?".json":fmt==DataPersistence.Format.XML?".xml":".ser");
        fc.setSelectedFile(new File(def));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            try { persistence.save(weekSchedule, f, fmt); JOptionPane.showMessageDialog(this, "Exported to: " + f.getAbsolutePath(), "Export", JOptionPane.INFORMATION_MESSAGE); }
            catch (IOException ex) { Log.PERSIST.log(Level.SEVERE, "Export failed", ex); JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE); }
        }
    }

    private void onImport() {
        JFileChooser fc = new JFileChooser(); fc.setDialogTitle("Import Routine");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile(); DataPersistence.Format fmt = DataPersistence.formatFromFile(f);
            try {
                Map<String, DaySchedule<String>> incoming = persistence.load(f, fmt);
                int choice = JOptionPane.showConfirmDialog(this, "Replace current schedule? (Yes = replace, No = merge)", "Import", JOptionPane.YES_NO_CANCEL_OPTION);
                if (choice == JOptionPane.CANCEL_OPTION) return;
                if (choice == JOptionPane.YES_OPTION) {
                    Map<String, DaySchedule<String>> ordered = new LinkedHashMap<>();
                    for (String d : daysOfWeek) ordered.put(d, incoming.getOrDefault(d, new DaySchedule<>(d)));
                    weekSchedule = ordered;
                } else {
                    for (String d : daysOfWeek) {
                        DaySchedule<String> cur = weekSchedule.get(d); DaySchedule<String> inc = incoming.get(d);
                        if (inc != null) for (Task<String> t : inc.getTasks()) cur.addTask(new Task<>(t.getTaskName(), t.getTime(), t.getEndTime(), t.getPriority(), t.getCategory()));
                    }
                }
                loadDaySchedule(currentDay); updateDailyProgress();
                JOptionPane.showMessageDialog(this, "Import completed from: " + f.getAbsolutePath(), "Import", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) { Log.PERSIST.log(Level.SEVERE, "Import failed", ex); JOptionPane.showMessageDialog(this, "Import failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE); }
        }
    }

    private void onSaveDayTemplate() {
        String name = JOptionPane.showInputDialog(this, "Template name:");
        if (name == null || name.trim().isEmpty()) return;
        DaySchedule<String> schedule = weekSchedule.get(currentDay);
        templateManager.saveDayTemplate(name.trim(), schedule.getTasks());
        JOptionPane.showMessageDialog(this, "Saved template: " + name, "Templates", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onApplyTemplateToDays() {
        if (templateManager.getTemplateNames().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No templates available.", "Templates", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String name = (String) JOptionPane.showInputDialog(this, "Select template:", "Templates",
                JOptionPane.QUESTION_MESSAGE, null, templateManager.getTemplateNames().toArray(), null);
        if (name == null) return;
        List<Task<String>> tasks = templateManager.getTemplate(name);
        if (tasks == null || tasks.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Template is empty.", "Templates", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> days = showDaysSelectionDialog("Apply template to which days?");
        if (days == null || days.isEmpty()) return;

        for (String d : days) {
            for (Task<String> t : tasks) {
                weekSchedule.get(d).addTask(new Task<>(t.getTaskName(), t.getTime(), t.getEndTime(), t.getPriority(), t.getCategory()));
            }
        }
        loadDaySchedule(currentDay);
        updateDailyProgress();
        JOptionPane.showMessageDialog(this, "Applied template to selected days.", "Templates", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onDeleteTemplate() {
        if (templateManager.getTemplateNames().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No templates available.", "Templates", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String name = (String) JOptionPane.showInputDialog(this, "Delete which template?", "Templates",
                JOptionPane.QUESTION_MESSAGE, null, templateManager.getTemplateNames().toArray(), null);
        if (name == null) return;
        int c = JOptionPane.showConfirmDialog(this, "Delete template '" + name + "'?", "Confirm", JOptionPane.YES_NO_OPTION);
        if (c == JOptionPane.YES_OPTION) {
            templateManager.deleteTemplate(name);
            JOptionPane.showMessageDialog(this, "Deleted template: " + name, "Templates", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private List<String> showDaysSelectionDialog(String title) {
        JDialog dialog = new JDialog(this, title, true);
        JPanel panel = new JPanel(new BorderLayout(10, 10)); panel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        JList<String> list = new JList<>(daysOfWeek); list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); list.setSelectedIndices(new int[]{0,1,2,3,4});
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT)); JButton ok = new JButton("OK"); JButton cancel = new JButton("Cancel");
        final boolean[] okPressed = {false}; ok.addActionListener(e -> { okPressed[0]=true; dialog.dispose(); }); cancel.addActionListener(e -> dialog.dispose());
        buttons.add(ok); buttons.add(cancel); panel.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(panel); dialog.setSize(300, 300); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
        if (!okPressed[0]) return null; return list.getSelectedValuesList();
    }
    
    public static void main(String[] args) { SwingUtilities.invokeLater(() -> new Base()); }
}