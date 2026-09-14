package com.mosip.deletion.model;

/**
 * One targeted deletion within a module (a table or an object-store path).
 * `count` is how many rows/objects were removed; `error` is set only on failure.
 */
public class SubStep {
    private final String name;
    private int count;
    private boolean ok = true;
    private String error;

    public SubStep(String name) { this.name = name; }

    public SubStep ok(int count) { this.count = count; this.ok = true; return this; }

    public SubStep failed(String error) { this.ok = false; this.error = error; return this; }

    public String getName() { return name; }
    public int getCount() { return count; }
    public boolean isOk() { return ok; }
    public String getError() { return error; }
}
