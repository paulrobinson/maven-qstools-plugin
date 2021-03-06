/*
 * JBoss, Home of Professional Open Source
 * Copyright 2013, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.maven.plugins.qstools;

import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.name;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.siterenderer.Renderer;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.AbstractMavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.codehaus.plexus.PlexusContainer;
import org.jboss.jdf.stacks.client.StacksClient;
import org.jboss.jdf.stacks.model.Stacks;

/**
 * 
 * Generate the Quickstart reports with all Checks
 * 
 * @author Rafael Benevides
 * 
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.COMPILE, requiresProject = true, threadSafe = true, aggregator = true)
public class QSCheckerReporter extends AbstractMavenReport {

    @Component
    private PlexusContainer container;

    @Component
    private Renderer siteRenderer;

    @Component
    private MavenProject mavenProject;

    @Component
    private BuildPluginManager pluginManager;

    @Component
    private MavenSession mavenSession;

    @Parameter(property = "reactorProjects", readonly = true, required = true)
    private List<MavenProject> reactorProjects;

    /**
     * Overwrite the config file
     */
    @Parameter(property = "qstools.configFileURL",
        defaultValue = "https://raw.github.com/jboss-developer/maven-qstools-plugin/master/config/qstools_config.yaml")
    // @Parameter(property = "qstools.configFileURL", defaultValue =
    // "file:/Users/rafaelbenevides/projetos/jdf/quickstarts-checker/config/qstools_config.yaml")
    private URL configFileURL;

    /**
     * Overwrite the stacks file
     */
    @Parameter(property = "qstools.stacks.url")
    private URL stacksUrl;

    /**
     * Force Stacks reload
     */
    @Parameter(property = "qstools.stacks.reload")
    private boolean forceStacksReload;

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.maven.reporting.MavenReport#getDescription(java.util.Locale)
     */
    @Override
    public String getDescription(Locale locale) {
        return "Quickstarts violations";
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.maven.reporting.MavenReport#getName(java.util.Locale)
     */
    @Override
    public String getName(Locale locale) {
        return "Quickstarts Checker Report";
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.maven.reporting.MavenReport#getOutputName()
     */
    @Override
    public String getOutputName() {
        return "qschecker";
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.maven.reporting.AbstractMavenReport#getSiteRenderer()
     */
    @Override
    protected Renderer getSiteRenderer() {
        return siteRenderer;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.maven.reporting.AbstractMavenReport#getOutputDirectory()
     */
    @Override
    protected String getOutputDirectory() {
        return mavenProject.getModel().getReporting().getOutputDirectory();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.maven.reporting.AbstractMavenReport#getProject()
     */
    @Override
    protected MavenProject getProject() {
        return mavenProject;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.maven.reporting.AbstractMavenReport#executeReport(java.util.Locale)
     */
    @Override
    protected void executeReport(Locale locale) throws MavenReportException {
        try {
            configurePlugin();
            executeJXRAndSitePlugins();

            List<QSChecker> checkersFound = container.lookupList(QSChecker.class);
            // sort the checkers
            List<QSChecker> checkers = new ArrayList<QSChecker>(checkersFound);
            Collections.sort(checkers, new Comparator<QSChecker>() {

                @Override
                public int compare(QSChecker o1, QSChecker o2) {
                    return o1.getClass().getSimpleName().compareTo(o2.getClass().getSimpleName());
                }
            });

            Map<String, List<Violation>> globalFilesViolations = new TreeMap<String, List<Violation>>();
            getLog().info("Running Checkers: Somes checkers could be ignored for some projects.");
            for (QSChecker checker : checkers) {
                getLog().info("Running Checker: " + checker.getClass().getSimpleName());
                checker.resetViolationsQtd();
                Map<String, List<Violation>> checkerViolations = checker.check(mavenProject, mavenSession, reactorProjects, getLog());
                addCheckerViolationsToGlobalFilesViolations(globalFilesViolations, checkerViolations);
            }
            startReport(checkers, locale);
            doFileSummary(globalFilesViolations);
            doFileReports(globalFilesViolations);
            // Display both the file name and a link for browser access
            String reportName = mavenProject.getModel().getReporting().getOutputDirectory() + File.separator + getOutputName() + ".html";
            String msg = "Your report is ready at %1$s \n       " +
                "You can access the report using Chrome or Firefox at the following URL: \n" +
                "            file://%1$s";
            getLog().info(String.format(msg, reportName));
        } catch (Exception e) {
            throw new MavenReportException(e.getMessage(), e);
        }
        endReport();

    }

    /**
     * Check if a Custom Stacks URL was informed and configure Stacks client
     * 
     */
    private void configurePlugin() {
        getLog().info("Using the following QSTools config file: " + configFileURL);
        container.getContext().put(Constants.CONFIG_FILE_CONTEXT, configFileURL);

        StacksClient stacksClient = new StacksClient();
        if (stacksUrl != null) {
            stacksClient.getActualConfiguration().setUrl(stacksUrl);
        }
        if (forceStacksReload) {
            getLog().info("FORCE STACKS RELOAD: Local 24 hours cache erased! (stacks.yaml will be downloaded again)");
            stacksClient.getLocalCacheFile().delete();
        }
        getLog().info("Using the following Stacks YML file: " + stacksClient.getActualConfiguration().getUrl());
        Stacks stacks = stacksClient.getStacks();
        container.getContext().put(Constants.STACKS_CONTEXT, stacks);

        container.getContext().put(Constants.LOG_CONTEXT, getLog());
        container.getContext().put(Constants.MAVEN_SESSION_CONTEXT, mavenSession);
        container.getContext().put(Constants.IGNORED_QUICKSTARTS_CONTEXT, readIgnoredFile());
    }

    /**
     * @return
     * @throws IOException
     */
    private List<String> readIgnoredFile() {
        List<String> result = new ArrayList<String>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(".quickstarts_ignore"));
            while (br.ready()) {
                String line = br.readLine();
                result.add(line);
            }
        } catch (IOException e) {
            // Log it and continue. If there's no file, there's nothing to ignore.
            getLog().warn("No .quickstarts_ignore file found. Proceeding without one.");
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    getLog().error("Exception when closing BufferedReader", e);
                }
            }
        }
        return result;
    }

    /**
     * Add all violations found by a Checker to tha global File Violation
     * 
     * @param filesViolations
     * @param checkerViolations
     */
    private void addCheckerViolationsToGlobalFilesViolations(Map<String, List<Violation>> filesViolations, Map<String, List<Violation>> checkerViolations) {
        for (String file : checkerViolations.keySet()) {
            List<Violation> ckviolations = checkerViolations.get(file);
            if (filesViolations.get(file) == null) {
                getLog().debug("New violations for file: " + file);
                filesViolations.put(file, new ArrayList<Violation>());
            }
            filesViolations.get(file).addAll(ckviolations);
        }
    }

    /**
     * @throws MojoExecutionException
     * 
     */
    private void executeJXRAndSitePlugins() throws MojoExecutionException {
        // Execute JXR Plugin
        executeMojo(
            plugin(
                groupId("org.apache.maven.plugins"),
                artifactId("maven-jxr-plugin"),
                version("2.3")
            ),
            goal("aggregate"),
            configuration(),
            executionEnvironment(
                mavenProject,
                mavenSession,
                pluginManager));

        // Execute JXR Plugin for test sources
        executeMojo(
            plugin(
                groupId("org.apache.maven.plugins"),
                artifactId("maven-jxr-plugin"),
                version("2.3")
            ),
            goal("test-aggregate"),
            configuration(),
            executionEnvironment(
                mavenProject,
                mavenSession,
                pluginManager));

        // Execute Site Plugin
        executeMojo(
            plugin(
                groupId("org.apache.maven.plugins"),
                artifactId("maven-site-plugin"),
                version("2.2")
            ),
            goal("site"),
            configuration(
                element(name("generateReports"), "false"),
                element(name("generateSitemap"), "false")
            ),
            executionEnvironment(
                mavenProject,
                mavenSession,
                pluginManager));
    }

    /**
     * Prints a File and each violations it have.
     * 
     * @param fileViolations
     */
    private void doFileReports(Map<String, List<Violation>> fileViolations) {
        Sink sink = getSink();

        sink.section1(); // Start Section 1
        sink.sectionTitle1();
        sink.text("Files Violations");
        sink.sectionTitle1_();

        // File Sections
        for (String file : fileViolations.keySet()) {
            sink.anchor(file.replace('/', '.'));
            sink.anchor_();

            sink.section2(); // Section 2 start
            sink.sectionTitle2();
            sink.text(file);
            sink.sectionTitle2_();

            sink.table();
            // Headers
            sink.tableRow();
            sink.tableHeaderCell();
            sink.text("Checker");
            sink.tableHeaderCell_();

            sink.tableHeaderCell();
            sink.text("Message");
            sink.tableHeaderCell_();

            sink.tableHeaderCell();
            sink.text("Line num.");
            sink.tableHeaderCell_();
            sink.tableRow_();

            // Each file violation
            List<Violation> violations = fileViolations.get(file);
            for (Violation violation : violations) {

                sink.tableRow();

                sink.tableCell();
                sink.text(violation.getSourceChecker().getSimpleName());
                sink.tableCell_();

                sink.tableCell();
                sink.text(violation.getViolationMessage());
                sink.tableCell_();

                sink.tableCell();

                // Only Java files has XREF
                String expression = "src/main/java";
                int pathIndex = file.lastIndexOf(expression);
                if (pathIndex > 0) {
                    String path = file.substring(pathIndex + expression.length()).replaceAll("\\.java$", ".html");
                    File xrefSource = new File(mavenProject.getModel().getReporting().getOutputDirectory() + "/xref/" + path);
                    if (xrefSource.exists()) {
                        String linelink = xrefSource.getAbsolutePath() + "#" + violation.getLineNumber();
                        sink.link(linelink);
                    }
                }
                expression = "src/test/java";
                pathIndex = file.lastIndexOf(expression);
                if (pathIndex > 0) {
                    String path = file.substring(pathIndex + expression.length()).replaceAll("\\.java$", ".html");
                    File xrefTestSource = new File(mavenProject.getModel().getReporting().getOutputDirectory() + "/xref-test/" + path);
                    if (xrefTestSource.exists()) {
                        String linelink = xrefTestSource.getAbsolutePath() + "#" + violation.getLineNumber();
                        sink.link(linelink);
                    }
                }
                sink.text(String.valueOf(violation.getLineNumber()));
                sink.link_();
                sink.tableCell_();

                sink.tableRow_();
            }
            sink.table_();
            sink.section2_(); // End Section 2
        }
        sink.section1_(); // End Section 1
    }

    /**
     * Prints file summary
     * 
     * @param filesViolations
     */
    private void doFileSummary(Map<String, List<Violation>> filesViolations) {
        Sink sink = getSink();
        sink.section1(); // Start Section 1
        sink.sectionTitle1();
        sink.text("Files");
        sink.sectionTitle2_();

        sink.table();
        // Headers
        sink.tableRow();
        sink.tableHeaderCell();
        sink.text("File");
        sink.tableHeaderCell_();

        sink.tableHeaderCell();
        sink.text("Violations qtd.");
        sink.tableHeaderCell_();
        sink.tableRow();

        for (String file : filesViolations.keySet()) {
            sink.tableRow();
            sink.tableCell();
            sink.link("#" + file.replace('/', '.'));
            sink.text(file);
            sink.link_();
            sink.tableCell_();

            sink.tableCell();
            sink.text(String.valueOf(filesViolations.get(file).size()));
            sink.tableCell_();
            sink.tableRow();

        }
        sink.table_();
        sink.section1_(); // End Section 1

    }

    /**
     * Start the Reporter HTML
     * 
     * @param checkers
     * 
     * @param locale
     * @param sink
     * 
     */
    private void startReport(List<QSChecker> checkers, Locale locale) {
        Sink sink = getSink();
        sink.head();
        sink.title();
        sink.text(getName(locale));
        sink.title_();
        sink.head_();
        sink.body();

        sink.section1(); // Section 1 Start
        sink.sectionTitle1();
        sink.text("Quickstart Check Results");
        sink.sectionTitle1_();

        sink.text("The following checkers were used: ");
        sink.table();
        // Headers
        sink.tableRow();
        sink.tableHeaderCell();
        sink.text("Checker");
        sink.tableHeaderCell_();

        sink.tableHeaderCell();
        sink.text("Description");
        sink.tableHeaderCell_();

        sink.tableHeaderCell();
        sink.text("Violations qtd.");
        sink.tableHeaderCell_();
        sink.tableRow();

        for (QSChecker checker : checkers) {
            sink.tableRow();
            sink.tableCell();
            sink.bold();
            sink.text(checker.getClass().getSimpleName());
            sink.bold_();
            sink.link_();
            sink.tableCell_();

            sink.tableCell();
            sink.text(checker.getCheckerDescription());
            sink.tableCell_();

            sink.tableCell();
            sink.text(String.valueOf(checker.getViolatonsQtd()));
            sink.tableCell_();

            sink.tableRow();
        }
        sink.table_();

        sink.section1_(); // Section 1 End
    }

    /**
     * End the HTML report
     * 
     */
    private void endReport() {
        Sink sink = getSink();
        sink.body_();
        sink.flush();
        sink.close();
    }

}
