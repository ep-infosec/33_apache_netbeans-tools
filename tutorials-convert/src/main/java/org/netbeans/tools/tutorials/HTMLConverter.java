/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package org.netbeans.tools.tutorials;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.eclipse.mylyn.wikitext.parser.HtmlParser;
import org.xml.sax.InputSource;

/**
 * Converts HTML files in a source directory to ASCIIDOC files in a destination
 * directory.
 *
 * @author Antonio Vieiro <vieiro@apache.org>
 */
public class HTMLConverter {

	/**
	 * Depth of directory nesting.
	 * Set to 1 for debugging purposes, big number otherwise.
	 */
    private final static int DEPTH = 9999;

    private static final String APACHE_LICENSE_HEADER = ""
            + "\n"
            + "    Licensed to the Apache Software Foundation (ASF) under one\n"
            + "    or more contributor license agreements.  See the NOTICE file\n"
            + "    distributed with this work for additional information\n"
            + "    regarding copyright ownership.  The ASF licenses this file\n"
            + "    to you under the Apache License, Version 2.0 (the\n"
            + "    \"License\"); you may not use this file except in compliance\n"
            + "    with the License.  You may obtain a copy of the License at\n"
            + "\n"
            + "      http://www.apache.org/licenses/LICENSE-2.0\n"
            + "\n"
            + "    Unless required by applicable law or agreed to in writing,\n"
            + "    software distributed under the License is distributed on an\n"
            + "    \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY\n"
            + "    KIND, either express or implied.  See the License for the\n"
            + "    specific language governing permissions and limitations\n"
            + "    under the License.\n\n";

    private static final Logger LOG = Logger.getLogger(HTMLConverter.class.getName());
    private static Transformer transformer;
    private static DocumentBuilderFactory documentBuilderFactory;

    private static void convert(File docsTutorialsDocs, File docsTutorialsImages, File dest, ExternalLinksMap externalLinks) throws Exception {
        LOG.log(Level.INFO, "Converting tutorials from {0} to {1}", new Object[]{docsTutorialsDocs.getAbsolutePath(), dest.getAbsolutePath()});


        List<File> html_files = Files.find(docsTutorialsDocs.toPath(), DEPTH,
                (p, bfa) -> bfa.isRegularFile()).map(Path::toFile).filter((f) -> f.getName().endsWith(".html")).collect(Collectors.toList());

        URI baseDirectory = docsTutorialsDocs.toURI();
        int fileCount = 0;
        boolean debug=false;
        for (File htmlFile : html_files) {
            if (debug) {
                if (! htmlFile.getName().equals("annotations.html")) {
                    continue;
                }
            }
            String relativePath = baseDirectory.relativize(htmlFile.toURI()).getPath().replaceAll("\\.html", ".asciidoc");
            File asciidoc = new File(dest, relativePath);
            convertHTMLToAsciiDoc(dest, htmlFile, docsTutorialsImages, asciidoc, externalLinks);
            fileCount++;
        }
        LOG.log(Level.INFO, "Converted {0} tutorials.", fileCount);
    }
    
    private static void convertTrails(File docsTutorialsTrailsDirectory, File docsTutorialsImages, File dest, ExternalLinksMap externalLinks) throws Exception {
         LOG.log(Level.INFO, "Converting trails {0} to {1}", new Object[]{docsTutorialsTrailsDirectory.getAbsolutePath(), dest.getAbsolutePath()});

        List<File> html_files = Files.find(docsTutorialsTrailsDirectory.toPath(), DEPTH,
                (p, bfa) -> bfa.isRegularFile()).map(Path::toFile).filter((f) -> f.getName().endsWith(".html")).collect(Collectors.toList());

        URI baseDirectory = docsTutorialsTrailsDirectory.toURI();
        int fileCount = 0;
        boolean debug=false;
        for (File htmlFile : html_files) {
            if (debug) {
                if (! htmlFile.getName().equals("annotations.html")) {
                    continue;
                }
            }
            String relativePath = baseDirectory.relativize(htmlFile.toURI()).getPath().replaceAll("\\.html", ".asciidoc");
            File asciidoc = new File(dest, relativePath);
            convertHTMLToAsciiDocWithoutTables(dest, htmlFile, docsTutorialsImages, asciidoc, externalLinks);
            fileCount++;
        }
        LOG.log(Level.INFO, "Converted {0} trails.", fileCount);       
    }

    private static String asciidocHeader = null;

    private static String getAsciidocHeader() {
        if (asciidocHeader == null) {
            StringBuilder sb = new StringBuilder();
            String[] lines = APACHE_LICENSE_HEADER.split("\n");
            for (String line : lines) {
                sb.append("// ").append(line).append("\n");
            }
            sb.append("//\n\n");
            asciidocHeader = sb.toString();
        }
        return asciidocHeader;
    }

    private static ThreadLocal<SimpleDateFormat> sdf = null;

    private static final synchronized SimpleDateFormat getSimpleDateFormat() {
        if (sdf == null) {
            SimpleDateFormat d = new SimpleDateFormat("yyyy-MM-dd");
            sdf = new ThreadLocal<>();
            sdf.set(d);
        }
        return sdf.get();
    }

    /**
     * Converts the given HTML file to AsciiDoc format.
     * @param inputHTMLFile The input file.
     * @param imageDirectory The directory where images are to be found.
     * @param outputAsciidocFile The output asciidoc file.
     * @param externalLinks A map used to store external links detected in the HTML file.
     * @throws Exception on error.
     */
    private static void convertHTMLToAsciiDoc(File topDirectory, File inputHTMLFile, File imageDirectory, File outputAsciidocFile, ExternalLinksMap externalLinks) throws Exception {
        if (! outputAsciidocFile.getParentFile().exists()) {
            if (! outputAsciidocFile.getParentFile().mkdirs()) {
                throw new IOException(String.format("Cannot create directory '%s'", outputAsciidocFile.getParent()));
            }
        }
        System.out.format("Converting '%s'%n", inputHTMLFile.getAbsolutePath());
        try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputAsciidocFile), "utf-8"), 16 * 1024);
                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputHTMLFile), "utf-8"))) {
            output.write(getAsciidocHeader());

            CustomAsciiDocDocumentBuilder asciidocBuilder = new CustomAsciiDocDocumentBuilder(topDirectory, imageDirectory, outputAsciidocFile, output, externalLinks);
            InputSource source = new InputSource(reader);
            HtmlParser.instance().parse(source, asciidocBuilder);
            // HtmlParser.instanceWithHtmlCleanupRules().parse(source, asciidocBuilder);
        }
    }
    
    /**
     * Converts the given HTML file to AsciiDoc format, ignoring tables completely.
     * @param inputHTMLFile The input file.
     * @param imageDirectory The directory where images are to be found.
     * @param outputAsciidocFile The output asciidoc file.
     * @param externalLinks A map used to store external links detected in the HTML file.
     * @throws Exception on error.
     */
    private static void convertHTMLToAsciiDocWithoutTables(File topDirectory, File inputHTMLFile, File imageDirectory, File outputAsciidocFile, ExternalLinksMap externalLinks) throws Exception {
        if (! outputAsciidocFile.getParentFile().exists()) {
            if (! outputAsciidocFile.getParentFile().mkdirs()) {
                throw new IOException(String.format("Cannot create directory '%s'", outputAsciidocFile.getParent()));
            }
        }
        try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputAsciidocFile), "utf-8"), 16 * 1024);
                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputHTMLFile), "utf-8"))) {
            output.write(getAsciidocHeader());

            CustomAsciiDocDocumentBuilderWithoutTables asciidocBuilder = new CustomAsciiDocDocumentBuilderWithoutTables(topDirectory, imageDirectory, outputAsciidocFile, output, externalLinks);
            InputSource source = new InputSource(reader);
            HtmlParser.instance().parse(source, asciidocBuilder);
        }
    }
    
    private static void checkDirectoryExists(String message, File dir) throws Exception {
        String error = null;
        if (!dir.exists()) {
            error = String.format("%s '%s' does not exist.", message, dir.getAbsolutePath());
        }
        if (error == null && !dir.isDirectory()) {
            error = String.format("%s '%s' is not a directory.", message, dir.getAbsolutePath());
        }
        if (error == null && !dir.canRead()) {
            error = String.format("%s '%s' is not readable.", message, dir.getAbsolutePath());
        }
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
    }

    private static void usage() {

        System.err.println("Use: java " + HTMLConverter.class
                .getName() + " directory");
        System.err.println("   Where directory is the directory were you've clone:");
	System.err.println("      https://github.com/wadechandler/netbeans-static-site.git");
	System.err.println("See README.md for details.");
        System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            usage();
        }
        
        File cloneDirectory = new File(args[0]);
        checkDirectoryExists("Incorrect clone directory ", cloneDirectory);

	File srcContent = new File(new File(cloneDirectory, "src"), "content");
        checkDirectoryExists("Incorrect clone/src/content directory ", srcContent);

	File platform = new File(srcContent, "platform");
        checkDirectoryExists("Incorrect clone/src/content/platform directory ", platform);

	File platformTutorials = new File(platform, "tutorials");
        checkDirectoryExists("Incorrect clone/src/content/platform/tutorials directory ", platformTutorials);

	File platformImages = new File(platform, "images");
        checkDirectoryExists("Incorrect clone/src/content/platform/images directory ", platformImages);

	File platformGraph = new File(platform, "graph");
        checkDirectoryExists("Incorrect clone/src/content/platform/graph directory ", platformGraph);
        
        File currentDirectory = new File(System.getProperty("user.dir"));
        File dest = new File(currentDirectory, "tutorials-asciidoc");

        if (!dest.exists()) {
            if (!dest.mkdirs()) {
                throw new IllegalStateException("Cannot create directory " + dest.getAbsolutePath());
            }
        }

	File destTutorials = new File(dest, "tutorials");
        if (!destTutorials.exists()) {
            if (!destTutorials.mkdirs()) {
                throw new IllegalStateException("Cannot create directory " + destTutorials.getAbsolutePath());
            }
        }

	File destGraph = new File(dest, "graph");
        if (!destGraph.exists()) {
            if (!destGraph.mkdirs()) {
                throw new IllegalStateException("Cannot create directory " + destGraph.getAbsolutePath());
            }
        }


        ExternalLinksMap externalLinks = new ExternalLinksMap();

        convert(platformTutorials, platformImages, destTutorials, externalLinks);
        convert(platformGraph, platformGraph, destGraph, externalLinks);
         
        // convertTrails(cloneDirectory, docsTutorialsImagesDirectory, dest, externalLinks);

        LOG.info("Generating 'external-links.txt' with list of external links...");
        
        try ( PrintWriter ef = new PrintWriter(new FileWriter("external-links.yml"))) {
            for (String domain : externalLinks.getDomains()) {
                ef.format("- domain: \"%s\"%n", domain);
                ef.format("  links:%n");
                for (String href : externalLinks.getHrefs(domain)) {
                    ef.format("    link: \"%s\"%n", href);
                    ef.format("    used-at:%n");
                    for (String tutorial : externalLinks.getTutorials(href)) {
                        ef.format("      - \"%s\"%n", tutorial);
                    }
                }
            }
        }

        /* Remove a hand-made "content" section, that is not replaced by the asciidoc 'toc' stuff */
        Map<File, String> titles = AsciidocPostProcessor.removeContentSetcion(dest);
        
        /* Generate some "index.asciidoc" files with the list of tutorials on each directory. */
        AsciidocPostProcessor.generateIndexes(dest, titles);
        
    }

}
