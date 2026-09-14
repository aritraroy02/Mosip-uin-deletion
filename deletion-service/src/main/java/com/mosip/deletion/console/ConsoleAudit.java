package com.mosip.deletion.console;

import com.mosip.deletion.config.DeletionProperties;
import com.mosip.deletion.model.DeletionResult;
import com.mosip.deletion.model.ModuleResult;
import com.mosip.deletion.model.SubStep;
import com.mosip.deletion.service.DeletionContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Human-readable audit trail printed to the terminal running the service.
 *
 * This writes to System.out rather than through the logger on purpose: the
 * logger prefixes every line with a timestamp, thread and class name, which
 * destroys a fixed-width layout. The goal here is a terminal view an operator
 * can read at a glance, answering three questions for every request:
 *
 *   1. which UIN was asked for,
 *   2. which tables and buckets are about to be touched,
 *   3. whether each one actually gave up its data.
 *
 * Printing can never break a deletion: every method swallows its own errors.
 * Output is ASCII only so it renders correctly in a Windows console whatever
 * code page it happens to be using.
 */
@Component
public class ConsoleAudit {

    private static final int W = 78;
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final DeletionProperties props;

    public ConsoleAudit(DeletionProperties props) {
        this.props = props;
    }

    private boolean on() {
        return props.getDeletion().getConsoleAudit().isEnabled();
    }

    private boolean plain() {
        return props.getDeletion().getConsoleAudit().isShowPlainUin();
    }

    // ------------------------------------------------------------------ blocks

    /** Opening block: who is asking, for which UIN, and what it hashes to. */
    public void header(String uin, Integer saltBucket, String hashBare,
                       String hashPrefixed, String source) {
        if (!on()) return;
        try {
            StringBuilder b = new StringBuilder();
            b.append('\n').append("=".repeat(W)).append('\n');
            b.append("  UIN DELETION REQUEST").append('\n');
            b.append("=".repeat(W)).append('\n');
            field(b, "Requested via", source);
            field(b, "UIN (plain)", plain() ? uin : mask(uin));
            field(b, "Salt bucket", saltBucket == null ? "?" : String.valueOf(saltBucket));
            field(b, "UIN hash (bare)", hashBare == null ? "?" : hashBare);
            field(b, "UIN hash (prefixed)", hashPrefixed == null ? "?" : hashPrefixed);
            field(b, "Started", TS.format(Instant.now()) + " UTC");
            print(b);
        } catch (Throwable ignore) {
            // an audit line must never be the reason a deletion fails
        }
    }

    /** What the read-only resolution pass found before anything is deleted. */
    public void resolved(DeletionContext ctx) {
        if (!on()) return;
        try {
            StringBuilder b = new StringBuilder();
            section(b, "1", "RESOLVE   what this UIN maps to");
            count(b, "registration ids", ctx.regIds);
            count(b, "uin_ref_ids", ctx.uinRefIds);
            count(b, "virtual ids", ctx.vids);
            count(b, "handle hashes", ctx.handleHashes);
            count(b, "auth token ids", ctx.tokenIds);
            b.append(String.format("      %-20s : %s%n", "verdict",
                    ctx.hasAnyData() ? "DATA FOUND - proceeding to delete"
                                     : "NO DATA - nothing to delete"));
            print(b);
        } catch (Throwable ignore) {
        }
    }

    /** Every table and bucket this request intends to touch, before it touches them. */
    public void plan(List<DeletionPlan.Module> modules) {
        if (!on()) return;
        try {
            StringBuilder b = new StringBuilder();
            long running = modules.stream().filter(DeletionPlan.Module::willRun).count();
            long skipped = modules.size() - running;
            section(b, "2", "PLAN      what is about to be deleted");
            b.append(String.format("      %d targets across %d module(s), %d skipped%n%n",
                    DeletionPlan.targetCount(modules), running, skipped));

            for (DeletionPlan.Module m : modules) {
                if (!m.willRun()) {
                    b.append(String.format("      %-28s SKIPPED - %s%n%n", m.name(), m.skipReason()));
                    continue;
                }
                b.append(String.format("      %-28s %s%n", m.name(), m.stores()));
                int i = 1;
                for (DeletionPlan.Target t : m.targets()) {
                    b.append(String.format("      %2d. %-42s %s%n",
                            i++, fit(t.name(), 42), t.where()));
                }
                b.append('\n');
            }
            print(b);
        } catch (Throwable ignore) {
        }
    }

    /** Heading for the live execution block. */
    public void executeHeading() {
        if (!on()) return;
        try {
            StringBuilder b = new StringBuilder();
            section(b, "3", "EXECUTE   result of each target");
            print(b);
        } catch (Throwable ignore) {
        }
    }

    /** One module's actual outcome, printed as soon as that module finishes. */
    public void module(ModuleResult m) {
        if (!on()) return;
        try {
            StringBuilder b = new StringBuilder();
            b.append(String.format("      %s  [%s]%n", m.getModule(), m.getStatus()));
            if (m.getSubSteps().isEmpty()) {
                b.append("        (nothing attempted)\n");
            }
            for (SubStep s : m.getSubSteps()) {
                String name = fit(s.getName(), 46);
                if (!s.isOk()) {
                    b.append(String.format("        %-46s %-8s %s%n",
                            name, "FAILED", s.getError()));
                } else if (s.getCount() > 0) {
                    b.append(String.format("        %-46s %-8s %d%n",
                            name, "DELETED", s.getCount()));
                } else {
                    b.append(String.format("        %-46s %-8s %d%n",
                            name, "NOTHING", 0));
                }
            }
            print(b);
        } catch (Throwable ignore) {
        }
    }

    /** Closing block: per-module roll-up, overall verdict, audit row, timing. */
    public void result(DeletionResult result, long elapsedMs, boolean auditWritten, String auditError) {
        if (!on()) return;
        try {
            int removed = 0;
            int failed = 0;
            int ops = 0;
            for (ModuleResult m : result.modules()) {
                for (SubStep s : m.getSubSteps()) {
                    ops++;
                    if (s.isOk()) removed += s.getCount();
                    else failed++;
                }
            }

            StringBuilder b = new StringBuilder();
            section(b, "4", "RESULT");
            for (ModuleResult m : result.modules()) {
                String removedText = m.getSubSteps().isEmpty()
                        ? "-" : m.totalDeleted() + " removed";
                b.append(String.format("      %-24s %-10s %s%n",
                        m.getModule(), m.getStatus(), removedText));
            }
            b.append("      ").append("-".repeat(W - 10)).append('\n');
            field(b, "OVERALL", String.valueOf(result.overall()));
            field(b, "Rows/objects removed", removed + "  (from " + ops + " operations)");
            field(b, "Failed operations", String.valueOf(failed));
            field(b, "Audit record", auditWritten
                    ? "WRITTEN to mosip_deletion_audit  id=" + result.requestId()
                    : "NOT WRITTEN - " + auditError);
            field(b, "Elapsed", String.format("%.2f s", elapsedMs / 1000.0));
            b.append("=".repeat(W)).append('\n');
            print(b);
        } catch (Throwable ignore) {
        }
    }

    /** Used when the request stops before any module runs. */
    public void shortCircuit(String verdict, String message, String note) {
        if (!on()) return;
        try {
            StringBuilder b = new StringBuilder();
            section(b, "1", "RESULT    stopped before any deletion");
            field(b, "VERDICT", verdict);
            field(b, "Detail", message);
            field(b, "Tables touched", "none");
            b.append(String.format("      %s%n", note));
            b.append("=".repeat(W)).append('\n');
            print(b);
        } catch (Throwable ignore) {
        }
    }

    // ----------------------------------------------------------------- helpers

    private void section(StringBuilder b, String n, String title) {
        String head = "  [" + n + "] " + title + " ";
        b.append('\n').append(head).append("-".repeat(Math.max(0, W - head.length()))).append('\n');
    }

    private void field(StringBuilder b, String label, String value) {
        b.append(String.format("      %-20s : %s%n", label, value));
    }

    private void count(StringBuilder b, String label, Collection<String> values) {
        b.append(String.format("      %-20s : %-3d %s%n", label, values.size(), sample(values)));
    }

    private String sample(Collection<String> values) {
        if (values == null || values.isEmpty()) return "-";
        List<String> l = new ArrayList<>(values);
        String first = String.valueOf(l.get(0));
        if (first.length() > 40) first = first.substring(0, 37) + "...";
        return l.size() > 1 ? first + "  (+" + (l.size() - 1) + " more)" : first;
    }

    /** Keep a name inside its column so the status column stays aligned. */
    private String fit(String s, int width) {
        if (s == null) return "";
        return s.length() <= width ? s : s.substring(0, width - 2) + "..";
    }

    private String mask(String uin) {
        if (uin == null || uin.length() < 4) return "****";
        return "*".repeat(uin.length() - 4) + uin.substring(uin.length() - 4);
    }

    /** One synchronized write so concurrent requests cannot interleave mid-block. */
    private void print(StringBuilder b) {
        synchronized (ConsoleAudit.class) {
            System.out.print(b);
            System.out.flush();
        }
    }
}
