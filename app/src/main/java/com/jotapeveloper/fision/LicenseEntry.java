package com.jotapeveloper.fision;

/** Single third-party dependency metadata for the licenses screen. */
public class LicenseEntry {
    public final String name;
    public final String author;
    public final String license;   // short type: "MIT", "Apache 2.0", "LGPL 2.1", ...
    public final String url;       // project home page or repository
    public final String fullText;  // full licence body

    public LicenseEntry(String name, String author, String license,
                        String url, String fullText) {
        this.name = name;
        this.author = author;
        this.license = license;
        this.url = url;
        this.fullText = fullText;
    }
}
