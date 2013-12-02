package org.jboss.maven.plugins.qstoolsc.common;

import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.jboss.maven.plugins.qstools.QSCheckerException;
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

/**
 * @author paul.robinson@redhat.com 02/12/2013
 */
@Component(role = ArtifactIdNameUtil.class)
public class ArtifactIdNameUtil {

    protected XPath xPath = XPathFactory.newInstance().newXPath();

    private int violationsQtd;

    @Requirement
    private ConfigurationProvider configurationProvider;

    public List<PomInformation> findAllIncorrectArtifactIdNames(List<MavenProject> reactorProjects, Rules rules) throws Exception {

        List<PomInformation> incorrectNames = new ArrayList<PomInformation>();

        try {
            File rootDirOfQuickstarts = getRootDirOfQuickstarts(reactorProjects.get(0));
            String artifactIdPrefix = rules.getArtifactIdPrefix();

            for (MavenProject subProject : reactorProjects) {

                Document doc = PositionalXMLReader.readXML(new FileInputStream(subProject.getFile()));

                String expectedArtifactId = createArtifactId(artifactIdPrefix, rootDirOfQuickstarts, subProject.getBasedir());
                Node actualArtifactId = ((Node) xPath.evaluate("/project/artifactId", doc, XPathConstants.NODE));

                if (!expectedArtifactId.equals(actualArtifactId.getTextContent())) {

                    int lineNumber = getLineNumberFromNode(actualArtifactId);
                    incorrectNames.add(new PomInformation(subProject, lineNumber, expectedArtifactId, actualArtifactId.getTextContent()));
                }

            }
            return incorrectNames;
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

    public class PomInformation {

        private MavenProject project;

        private int line;

        private String expectedArtifactId;

        private String actualArtifactId;

        public PomInformation(MavenProject project, int line, String expectedArtifactId, String actualArtifactId) {

            this.project = project;
            this.line = line;
            this.expectedArtifactId = expectedArtifactId;
            this.actualArtifactId = actualArtifactId;
        }

        public MavenProject getProject() {

            return project;
        }

        public int getLine() {

            return line;
        }

        public String getExpectedArtifactId() {

            return expectedArtifactId;
        }

        public String getActualArtifactId() {

            return actualArtifactId;
        }
    }
}
