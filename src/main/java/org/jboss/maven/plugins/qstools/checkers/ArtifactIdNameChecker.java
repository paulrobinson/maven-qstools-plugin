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
package org.jboss.maven.plugins.qstools.checkers;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.jboss.maven.plugins.qstools.QSChecker;
import org.jboss.maven.plugins.qstools.QSCheckerException;
import org.jboss.maven.plugins.qstools.Violation;
import org.jboss.maven.plugins.qstools.config.ConfigurationProvider;
import org.jboss.maven.plugins.qstools.config.Rules;
import org.jboss.maven.plugins.qstools.xml.PositionalXMLReader;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * @author Rafael Benevides
 * @author Paul Robinson
 */
@Component(role = QSChecker.class, hint = "artifactIdNameChecker")
public class ArtifactIdNameChecker implements QSChecker {

    protected XPath xPath = XPathFactory.newInstance().newXPath();

    private int violationsQtd;

    @Requirement
    private ConfigurationProvider configurationProvider;

    /*
     * (non-Javadoc)
     *
     * @see org.jboss.maven.plugins.qstools.QSChecker#getViolatonsQtd()
     */
    @Override
    public int getViolatonsQtd() {

        return violationsQtd;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.jboss.maven.plugins.qstools.QSChecker#resetViolationsQtd()
     */
    @Override
    public void resetViolationsQtd() {

        violationsQtd = 0;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.jboss.maven.plugins.qstools.QSChecker#getCheckerDescription()
     */
    @Override
    public String getCheckerDescription() {

        return "Check if the Maven ArtifactId uses a valid name";
    }

    /*
     * (non-Javadoc)
     *
     * @see org.jboss.maven.plugins.qstools.QSChecker#check(org.apache.maven.project.MavenProject,
     * org.apache.maven.execution.MavenSession, java.util.List, org.apache.maven.plugin.logging.Log)
     */
    @Override
    public Map<String, List<Violation>> check(MavenProject project, MavenSession mavenSession, List<MavenProject> reactorProjects, Log log) throws QSCheckerException {

        try {
            Map<String, List<Violation>> results = new TreeMap<String, List<Violation>>();
            File rootDirOfQuickstarts = getRootDirOfQuickstarts(project);
            Rules rules = configurationProvider.getQuickstartsRules(project.getGroupId());
            String artifactIdPrefix = rules.getArtifactIdPrefix();

            for (MavenProject subProject : reactorProjects) {

                Document doc = PositionalXMLReader.readXML(new FileInputStream(subProject.getFile()));

                String expectedArtifactId = createArtifactId(artifactIdPrefix, rootDirOfQuickstarts, subProject.getBasedir());
                Node actualArtifactId = ((Node) xPath.evaluate("/project/artifactId", doc, XPathConstants.NODE));

                if (!expectedArtifactId.equals(actualArtifactId.getTextContent())) {

                    int lineNumber = getLineNumberFromNode(actualArtifactId);
                    String rootDirectory = (mavenSession.getExecutionRootDirectory() + File.separator).replace("\\", "\\\\");
                    String fileAsString = subProject.getFile().getAbsolutePath().replace(rootDirectory, "");
                    if (results.get(fileAsString) == null) {
                        results.put(fileAsString, new ArrayList<Violation>());
                    }
                    String msg = "Project with the following artifactId [%s] doesn't match the required format. It should be: [%s]";
                    results.get(fileAsString).add(new Violation(getClass(), lineNumber, String.format(msg, actualArtifactId.getTextContent(), expectedArtifactId)));
                    violationsQtd++;
                }

            }
            return results;
        } catch (Exception e) {
            throw new QSCheckerException(e);
        }
    }

    /**
     * (non-Javadoc)
     * <p/>
     * Walks up the parent directories looking for the last containing a pom.xml with the same groupId as the
     * specified project.
     */
    private File getRootDirOfQuickstarts(MavenProject project) throws Exception {

        File dirToCheck = project.getBasedir();
        File resultDir = project.getBasedir();
        while (containsPomWithGroupId(dirToCheck, project.getGroupId())) {
            resultDir = dirToCheck;
            dirToCheck = dirToCheck.getParentFile();
        }

        return resultDir;
    }

    /**
     * (non-Javadoc)
     * <p/>
     * Checks if the specified dir contains a pom.xml and if so, whether it is related (same groupId)
     */
    private boolean containsPomWithGroupId(File dir, String expectedGroupId) throws Exception {

        File pom = new File(dir + File.separator + "pom.xml");

        if (!pom.exists()) {
            return false;
        }

        Document doc = PositionalXMLReader.readXML(new FileInputStream(pom));
        Node actualGroupId = (Node) xPath.evaluate("/project/groupId", doc, XPathConstants.NODE);

        //If groupId missing, then take from parent
        if (actualGroupId == null) {
            actualGroupId = (Node) xPath.evaluate("/project/parent/groupId", doc, XPathConstants.NODE);
        }

        return actualGroupId.getTextContent().equals(expectedGroupId);
    }

    private String createArtifactId(String artifactPrefix, File rootDirOfQuickstarts, File moduleBaseDir) {

        if (rootDirOfQuickstarts.equals(moduleBaseDir)) {
            return artifactPrefix + "quickstart-parent";
        } else {
            String modulePath = moduleBaseDir.getPath().substring(rootDirOfQuickstarts.getPath().length());
            //Remove preceding '-'
            modulePath = modulePath.substring(1);
            return artifactPrefix + modulePath.replace(File.separatorChar, '-');
        }
    }

    protected int getLineNumberFromNode(Node node) {

        if (node == null) {
            return 0;
        }
        return (Integer) node.getUserData(PositionalXMLReader.BEGIN_LINE_NUMBER_KEY_NAME);
    }
}
