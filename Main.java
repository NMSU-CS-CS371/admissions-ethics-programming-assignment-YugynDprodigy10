// Main.java
// Compare BLIND vs AWARE models with either top-K or cutoff admission.

import java.io.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Main {


    private static String[] parseCSVLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (char c : line.toCharArray()) {
            if (c == '"') inQuotes = !inQuotes;
            else if ((c == ',' || c == '\t') && !inQuotes) {
                out.add(sb.toString().trim());
                sb.setLength(0);
            } else sb.append(c);
        }
        out.add(sb.toString().trim());
        return out.toArray(new String[0]);
    }

    private static boolean parseYesNo(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase();
        return t.equals("yes") || t.equals("true") || t.equals("1");
    }

    private static double parseIncome(String s) {
        if (s == null) return 0.0;
        try {
            return Double.parseDouble(s.replace("$", "").replace(",", "").trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    public static List<Applicant> readApplicants(String filename) {
        List<Applicant> apps = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String header = br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] p = parseCSVLine(line);
                if (p.length < 14) continue; // skip malformed

                try {
                    String name = p[0];
                    int age = Integer.parseInt(p[1]);
                    String geography = p[2];
                    String ethnicity = p[3];
                    double income = parseIncome(p[4]);
                    boolean legacy = parseYesNo(p[5]);
                    boolean local = parseYesNo(p[6]);
                    double gpa = Double.parseDouble(p[7]);
                    int test = Integer.parseInt(p[8]);
                    double extra = Double.parseDouble(p[9]);
                    double essay = Double.parseDouble(p[10]);
                    double rec = Double.parseDouble(p[11]);
                    boolean firstGen = parseYesNo(p[12]);
                    boolean disability = parseYesNo(p[13]);

                    apps.add(new Applicant(
                        name, age, geography, ethnicity, income,
                        legacy, local, gpa, test, extra, essay, rec, firstGen, disability
                    ));
                } catch (Exception e) {
                    System.out.println("Skipping malformed row: " + line);
                }
            }
        } catch (IOException e) {
            System.out.println("Error reading file: " + e.getMessage());
        }
        return apps;
    }

    // ---------- Admission strategies ----------
    static class Row {
        Applicant a;
        double blind;
        double aware;
        boolean admitBlind;
        boolean admitAware;
        int rankBlind;
        int rankAware;
        Row(Applicant a, double b, double w) { this.a = a; this.blind = b; this.aware = w; }
    }

    private static void admitTopK(List<Row> rows, int K, boolean useAware) {
        rows.sort((r1, r2) -> {
            double s1 = useAware ? r1.aware : r1.blind;
            double s2 = useAware ? r2.aware : r2.blind;
            if (s1 == s2) {
                // stable tie-break: higher test, then higher GPA, then name
                if (r2.a.test != r1.a.test) return Integer.compare(r2.a.test, r1.a.test);
                if (r2.a.gpa != r1.a.gpa) return Double.compare(r2.a.gpa, r1.a.gpa);
                return r1.a.name.compareToIgnoreCase(r2.a.name);
            }
            return Double.compare(s2, s1);
        });

        for (int i = 0; i < rows.size(); i++) {
            if (useAware) {
                rows.get(i).rankAware = i + 1;
                rows.get(i).admitAware = (i < K);
            } else {
                rows.get(i).rankBlind = i + 1;
                rows.get(i).admitBlind = (i < K);
            }
        }
    }

    private static void admitByCutoff(List<Row> rows, double cutoff) {
        for (Row r : rows) {
            r.admitBlind = r.blind >= cutoff;
            r.admitAware = r.aware >= cutoff;
        }
    }

    // ---------- Fairness helpers ----------
    private static double rate(Collection<Row> rows, boolean aware) {
        long admits = rows.stream().filter(r -> aware ? r.admitAware : r.admitBlind).count();
        return (double) admits / rows.size();
    }

    private static <T> void printGroupRates(
            List<Row> rows, String title, Function<Applicant, T> keyFn) {

        System.out.println("\n" + title);
        Map<T, List<Row>> groups = rows.stream().collect(Collectors.groupingBy(r -> keyFn.apply(r.a)));
        double max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;

        for (Map.Entry<T, List<Row>> e : groups.entrySet()) {
            double rb = rate(e.getValue(), false);
            double ra = rate(e.getValue(), true);
            max = Math.max(max, ra);
            min = Math.min(min, ra);
            System.out.printf("  %-12s | BLIND: %.3f  AWARE: %.3f  (n=%d)\n",
                    String.valueOf(e.getKey()), rb, ra, e.getValue().size());
        }
        System.out.printf("  Demographic parity gap (AWARE): %.3f\n", (max - min));
    }

    private static String incomeBracket(double income) {
        if (income < 40000) return "Low";
        if (income < 100000) return "Middle";
        return "High";
    }

    // ---------- Output CSV ----------
    private static void writeResultsCSV(String path, List<Row> rows) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            pw.println("name,gpa,test,income,legacy,firstGen,disability,blindScore,awareScore,admitBlind,admitAware,rankBlind,rankAware");
            for (Row r : rows) {
                pw.printf("%s,%.2f,%d,%.2f,%s,%s,%s,%.4f,%.4f,%s,%s,%d,%d%n",
                        r.a.name, r.a.gpa, r.a.test, r.a.income,
                        r.a.legacy, r.a.firstGen, r.a.disability,
                        r.blind, r.aware, r.admitBlind, r.admitAware,
                        r.rankBlind, r.rankAware);
            }
        } catch (IOException e) {
            System.out.println("Could not write results.csv: " + e.getMessage());
        }
    }

    // ---------- Main ----------
    public static void main(String[] args) {
        List<Applicant> applicants = readApplicants("applicants.csv");
        if (applicants.isEmpty()) {
            System.out.println("No applicants found. Check CSV header and path.");
            return;
        }

        // Compute scores
        List<Row> rows = new ArrayList<>();
        for (Applicant a : applicants) {
            rows.add(new Row(a, Admissions.blindScore(a), Admissions.awareScore(a)));
        }

        // Parse args for K or cutoff
        Integer K = 120;          // default top-K
        Double cutoff = null;     // if provided, use cutoff instead
        for (String arg : args) {
            if (arg.startsWith("--k=")) {
                K = Integer.parseInt(arg.substring(4));
            } else if (arg.startsWith("--cutoff=")) {
                cutoff = Double.parseDouble(arg.substring(9));
                K = null; // ignore K if cutoff is specified
            }
        }

        if (cutoff != null) {
            admitByCutoff(rows, cutoff);
        } else {
            // do top-K for each model independently
            admitTopK(rows, K, false); // BLIND
            admitTopK(rows, K, true);  // AWARE
        }

        // Report
        System.out.println("=== Ethical Admissions Results ===");
        System.out.printf("Applicants: %d%n", rows.size());
        if (cutoff != null) System.out.printf("Cutoff: %.3f%n", cutoff);
        else                System.out.printf("Top-K:  %d%n", K);

        System.out.printf("Overall admit rate BLIND: %.3f%n", rate(rows, false));
        System.out.printf("Overall admit rate AWARE: %.3f%n", rate(rows, true));

        printGroupRates(rows, "By First-Gen", a -> a.firstGen ? "FirstGen" : "NonFirstGen");
        printGroupRates(rows, "By Legacy",    a -> a.legacy ? "Legacy" : "NonLegacy");
        printGroupRates(rows, "By Income",    a -> incomeBracket(a.income));

        writeResultsCSV("results.csv", rows);
        System.out.println("\nSaved: results.csv");
    }
}
