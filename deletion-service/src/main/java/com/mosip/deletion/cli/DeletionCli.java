package com.mosip.deletion.cli;

import com.mosip.deletion.model.CheckResult;
import com.mosip.deletion.model.DeletionResult;
import com.mosip.deletion.model.ModuleResult;
import com.mosip.deletion.model.SubStep;
import com.mosip.deletion.service.DeletionService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Interactive terminal flow, enabled with the "cli" profile:
 *     java -jar app.jar --spring.profiles.active=cli
 *
 * Implements exactly: enter UIN -> check availability -> if available ask
 * consent (yes/no) -> delete and print the module-wise status. The web API
 * stays available; this just adds a console driver on top of the same service.
 */
@Component
@Profile("cli")
public class DeletionCli implements ApplicationRunner {

    private final DeletionService service;

    public DeletionCli(DeletionService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("=== MOSIP Self-Service UIN Deletion (terminal) ===");
        while (true) {
            System.out.print("\nEnter UIN (or 'quit'): ");
            String uin = readLine(in);
            if (uin == null || uin.equalsIgnoreCase("quit")) {
                System.out.println("bye");
                return;
            }
            uin = uin.trim();
            if (!uin.matches("\\d+")) {
                System.out.println("  ! UIN must be numeric.");
                continue;
            }
            handle(uin, in);
        }
    }

    private void handle(String uin, BufferedReader in) throws Exception {
        CheckResult check;
        try {
            check = service.check(uin);
        } catch (Exception e) {
            System.out.println("  ! error checking UIN: " + e.getMessage());
            return;
        }
        switch (check.availability()) {
            case NO_DATA_AVAILABLE:
                System.out.println("  > No data available for this UIN.");
                return;
            case ALREADY_DELETED:
                System.out.println("  > " + check.message());
                return;
            case AVAILABLE:
                System.out.println("  > Data found: " + check.summary());
        }

        System.out.print("  Delete all data for this UIN? Consent (yes/no): ");
        String consent = readLine(in);
        if (consent == null || !consent.trim().equalsIgnoreCase("yes")) {
            System.out.println("  > Aborted. No data was deleted.");
            return;
        }

        DeletionResult result = service.delete(uin);
        System.out.println("\n  === Deletion status (overall: " + result.overall() + ") ===");
        for (ModuleResult m : result.modules()) {
            System.out.printf("   %-18s %-10s (%d removed)%n",
                    m.getModule(), m.getStatus(), m.totalDeleted());
            for (SubStep s : m.getSubSteps()) {
                if (!s.isOk()) {
                    System.out.printf("       ! %-45s FAILED: %s%n", s.getName(), s.getError());
                } else if (s.getCount() > 0) {
                    System.out.printf("       - %-45s %d%n", s.getName(), s.getCount());
                }
            }
        }
        System.out.println("  request id: " + result.requestId());
    }

    private String readLine(BufferedReader in) throws Exception {
        return in.readLine();
    }
}
