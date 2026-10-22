package app;

import java.util.*;
import java.util.regex.*;


public class Scorer {
    public record Result(double score, Map<String, Object> details) {}

    static final Map<String, String> ALIAS = Map.of(
            "js", "javascript", "nodejs", "node.js", "reactjs", "react", "postgres", "postgresql",
            "k8s", "kubernetes", "ml", "machine learning", "golang", "go");

    static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "are", "was", "from",
            "have", "has", "you", "your", "our", "will", "can", "all", "any", "not", "but", "per", "etc");

    static String canon(String s) {
        s = s.toLowerCase().trim();
        return ALIAS.getOrDefault(s, s);
    }

    static Set<String> skillSet(String csv) {
        Set<String> r = new LinkedHashSet<>();
        if (csv == null) return r;
        for (String p : csv.split("[,;\n]")) {
            String c = canon(p);
            if (!c.isEmpty()) r.add(c);
        }
        return r;
    }

    static double skillMatchPercent(Set<String> mine, Set<String> req) {
        if (req.isEmpty()) return 0.0;
        long n = req.stream().filter(mine::contains).count();
        return Math.round(1000.0 * n / req.size()) / 10.0;
    }

    static boolean has(String text, String skill) {
        List<String> forms = new ArrayList<>();
        forms.add(skill);
        ALIAS.forEach((a, c) -> { if (c.equals(skill)) forms.add(a); });
        for (String f : forms) {
            Pattern p = Pattern.compile("(?<![a-z0-9+#])" + Pattern.quote(f) + "(?![a-z0-9+#])");
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    static Map<String, Integer> tf(String text) {
        Map<String, Integer> m = new HashMap<>();
        for (String w : text.split("[^a-z0-9+#]+")) {
            if (w.length() > 2 && !STOP.contains(w)) m.merge(w, 1, Integer::sum);
        }
        return m;
    }

    static double cosine(String a, String b) {
        Map<String, Integer> x = tf(a), y = tf(b);
        double dot = 0, nx = 0, ny = 0;
        for (var e : x.entrySet()) {
            nx += e.getValue() * e.getValue();
            Integer o = y.get(e.getKey());
            if (o != null) dot += e.getValue() * o;
        }
        for (int v : y.values()) ny += v * v;
        return (nx == 0 || ny == 0) ? 0 : dot / (Math.sqrt(nx) * Math.sqrt(ny));
    }

    static int years(String text) {
        Matcher m = Pattern.compile("(\\d{1,2})\\+?\\s*(?:years?|yrs?)").matcher(text);
        int best = 0;
        while (m.find()) best = Math.max(best, Integer.parseInt(m.group(1)));
        return best;
    }

    static double structure(String text) {
        String[] sections = {"education", "experience", "project", "skills", "certification"};
        int n = 0;
        for (String s : sections) if (text.contains(s)) n++;
        if (Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}").matcher(text).find()) n++;
        return n / 6.0;
    }

    static double r1(double v) { return Math.round(v * 10.0) / 10.0; }

    public static Result score(String resume, String title, String desc, String reqCsv, int minExp) {
        String text = resume.toLowerCase();
        Set<String> req = skillSet(reqCsv);
        List<String> matched = new ArrayList<>(), missing = new ArrayList<>();
        for (String s : req) (has(text, s) ? matched : missing).add(s);

        double skill = req.isEmpty() ? 0.5 : (double) matched.size() / req.size();
        double sim = Math.min(1.0, cosine(text, (title + " " + desc + " " + reqCsv).toLowerCase()) / 0.4);
        int yrs = years(text);
        double exp = minExp <= 0 ? 1.0 : Math.min(1.0, (double) yrs / minExp);
        double struct = structure(text);
        double total = 100 * (0.5 * skill + 0.25 * sim + 0.15 * exp + 0.10 * struct);

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("skills", r1(skill * 100));
        d.put("similarity", r1(sim * 100));
        d.put("experience", r1(exp * 100));
        d.put("structure", r1(struct * 100));
        d.put("yearsFound", yrs);
        d.put("matched", matched);
        d.put("missing", missing);
        return new Result(r1(total), d);
    }
}
