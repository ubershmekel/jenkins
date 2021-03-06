/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.model;

import com.gargoylesoftware.htmlunit.ElementNotFoundException
import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.HttpMethod
import com.gargoylesoftware.htmlunit.WebRequest;
import hudson.tasks.BatchFile;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Recorder;
import com.gargoylesoftware.htmlunit.html.HtmlPage
import hudson.maven.MavenModuleSet;
import hudson.security.*;
import hudson.tasks.Shell;
import hudson.scm.NullSCM;
import hudson.scm.SCM
import hudson.scm.SCMDescriptor
import hudson.Launcher;
import hudson.FilePath;
import hudson.Functions;
import hudson.Util;
import hudson.tasks.ArtifactArchiver
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger
import hudson.triggers.Trigger
import hudson.triggers.TriggerDescriptor;
import hudson.util.StreamTaskListener;
import hudson.util.OneShotEvent
import jenkins.model.Jenkins
import org.junit.Rule
import org.junit.Test;
import org.jvnet.hudson.test.Issue
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.recipes.PresetData;
import org.jvnet.hudson.test.recipes.PresetData.DataSet
import org.apache.commons.io.FileUtils;
import org.junit.Assume;
import org.jvnet.hudson.test.MockFolder
import org.kohsuke.args4j.CmdLineException

import static org.junit.Assert.fail
import static org.junit.Assert.assertEquals;

/**
 * @author Kohsuke Kawaguchi
 */
public class AbstractProjectTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    @Test
    public void testConfigRoundtrip() {
        def project = j.createFreeStyleProject();
        def l = j.jenkins.getLabel("foo && bar");
        project.assignedLabel = l;
        j.configRoundtrip((Item) project);

        assert l == project.getAssignedLabel();
    }

    /**
     * Tests the workspace deletion.
     */
    @Test
    public void testWipeWorkspace() {
        def project = j.createFreeStyleProject();
        project.buildersList.add(Functions.isWindows() ? new BatchFile("echo hello") : new Shell("echo hello"));

        def b = project.scheduleBuild2(0).get();

        assert b.workspace.exists(): "Workspace should exist by now";

        project.doDoWipeOutWorkspace();

        assert !b.workspace.exists(): "Workspace should be gone by now";
    }

    /**
     * Makes sure that the workspace deletion is protected.
     */
    @Test
    @PresetData(DataSet.NO_ANONYMOUS_READACCESS)
    public void testWipeWorkspaceProtected() {
        def project = j.createFreeStyleProject();
        project.getBuildersList().add(Functions.isWindows() ? new BatchFile("echo hello") : new Shell("echo hello"));

        def b = project.scheduleBuild2(0).get();

        assert b.getWorkspace().exists(): "Workspace should exist by now";

        // make sure that the action link is protected
        com.gargoylesoftware.htmlunit.WebClient wc = j.createWebClient();
        try {
            wc.getPage(new WebRequest(new URL(wc.getContextPath() + project.getUrl() + "doWipeOutWorkspace"), HttpMethod.POST));
            fail("Expected HTTP status code 403")
        } catch (FailingHttpStatusCodeException e) {
            assertEquals(HttpURLConnection.HTTP_FORBIDDEN, e.getStatusCode());
        }
    }

    /**
     * Makes sure that the workspace deletion link is not provided
     * when the user doesn't have an access.
     */
    @Test
    @PresetData(DataSet.ANONYMOUS_READONLY)
    public void testWipeWorkspaceProtected2() {
        ((GlobalMatrixAuthorizationStrategy) j.jenkins.getAuthorizationStrategy()).add(AbstractProject.WORKSPACE,"anonymous");

        // make sure that the deletion is protected in the same way
        testWipeWorkspaceProtected();

        // there shouldn't be any "wipe out workspace" link for anonymous user
        def webClient = j.createWebClient();
        HtmlPage page = webClient.getPage(j.jenkins.getItem("test0"));

        page = (HtmlPage)page.getAnchorByText("Workspace").click();
        try {
        	String wipeOutLabel = ResourceBundle.getBundle("hudson/model/AbstractProject/sidepanel").getString("Wipe Out Workspace");
		page.getAnchorByText(wipeOutLabel);
            fail("shouldn't find a link");
        } catch (ElementNotFoundException e) {
            // OK
        }
    }

    /**
     * Tests the &lt;optionalBlock @field> round trip behavior by using {@link AbstractProject#concurrentBuild}
     */
    @Test
    public void testOptionalBlockDataBindingRoundtrip() {
        def p = j.createFreeStyleProject();
        [true,false].each { b ->
            p.concurrentBuild = b;
            j.submit(j.createWebClient().getPage(p,"configure").getFormByName("config"));
            assert b==p.isConcurrentBuild();
        }
    }

    /**
     * Tests round trip configuration of the blockBuildWhenUpstreamBuilding field
     */
    @Test
    @Issue("JENKINS-4423")
    public void testConfiguringBlockBuildWhenUpstreamBuildingRoundtrip() {
        def p = j.createFreeStyleProject();
        p.blockBuildWhenUpstreamBuilding = false;

        def form = j.createWebClient().getPage(p, "configure").getFormByName("config");
        def input = form.getInputByName("blockBuildWhenUpstreamBuilding");
        assert !input.isChecked(): "blockBuildWhenUpstreamBuilding check box is checked.";

        input.setChecked(true);
        j.submit(form);
        assert p.blockBuildWhenUpstreamBuilding: "blockBuildWhenUpstreamBuilding was not updated from configuration form";

        form = j.createWebClient().getPage(p, "configure").getFormByName("config");
        input = form.getInputByName("blockBuildWhenUpstreamBuilding");
        assert input.isChecked(): "blockBuildWhenUpstreamBuilding check box is not checked.";
    }

    /**
     * Unless the concurrent build option is enabled, polling and build should be mutually exclusive
     * to avoid allocating unnecessary workspaces.
     */
    @Test
    @Issue("JENKINS-4202")
    public void testPollingAndBuildExclusion() {
        final OneShotEvent sync = new OneShotEvent();

        final FreeStyleProject p = j.createFreeStyleProject();
        def b1 = j.buildAndAssertSuccess(p);

        p.scm = new NullSCM() {
            @Override
            public boolean pollChanges(AbstractProject project, Launcher launcher, FilePath workspace, TaskListener listener) {
                try {
                    sync.block();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }

            /**
             * Don't write 'this', so that subtypes can be implemented as anonymous class.
             */
            private Object writeReplace() { return new Object(); }

            @Override public boolean requiresWorkspaceForPolling() {
                return true;
            }
            @Override public SCMDescriptor<?> getDescriptor() {
                return new SCMDescriptor<SCM>(null) {};
            }
        };
        Thread t = new Thread() {
            @Override public void run() {
                p.pollSCMChanges(StreamTaskListener.fromStdout());
            }
        };
        try {
            t.start();
            def f = p.scheduleBuild2(0);

            // add a bit of delay to make sure that the blockage is happening
            Thread.sleep(3000);

            // release the polling
            sync.signal();

            def b2 = j.assertBuildStatusSuccess(f);

            // they should have used the same workspace.
            assert b1.workspace == b2.workspace;
        } finally {
            t.interrupt();
        }
    }

    @Test
    @Issue("JENKINS-1986")
    public void testBuildSymlinks() {
        Assume.assumeFalse("If we're on Windows, don't bother doing this", Functions.isWindows());

        def job = j.createFreeStyleProject();
        job.buildersList.add(new Shell("echo \"Build #\$BUILD_NUMBER\"\n"));
        def build = job.scheduleBuild2(0, new Cause.UserCause()).get();
        File lastSuccessful = new File(job.rootDir, "lastSuccessful"),
             lastStable = new File(job.rootDir, "lastStable");
        // First build creates links
        assertSymlinkForBuild(lastSuccessful, 1);
        assertSymlinkForBuild(lastStable, 1);
        FreeStyleBuild build2 = job.scheduleBuild2(0, new Cause.UserCause()).get();
        // Another build updates links
        assertSymlinkForBuild(lastSuccessful, 2);
        assertSymlinkForBuild(lastStable, 2);
        // Delete latest build should update links
        build2.delete();
        assertSymlinkForBuild(lastSuccessful, 1);
        assertSymlinkForBuild(lastStable, 1);
        // Delete all builds should remove links
        build.delete();
        assert !lastSuccessful.exists(): "lastSuccessful link should be removed";
        assert !lastStable.exists(): "lastStable link should be removed";
    }

    private static void assertSymlinkForBuild(File file, int buildNumber)
            throws IOException, InterruptedException {
        assert file.exists(): "should exist and point to something that exists";
        assert Util.isSymlink(file): "should be symlink";
        String s = FileUtils.readFileToString(new File(file, "log"));
        assert s.contains("Build #" + buildNumber + "\n") : "link should point to build #$buildNumber, but link was: ${Util.resolveSymlink(file, TaskListener.NULL)}\nand log was:\n$s";
    }

    @Test
    @Issue("JENKINS-2543")
    public void testSymlinkForPostBuildFailure() {
        Assume.assumeFalse("If we're on Windows, don't bother doing this", Functions.isWindows());

        // Links should be updated after post-build actions when final build result is known
        def job = j.createFreeStyleProject();
        job.buildersList.add(new Shell("echo \"Build #\$BUILD_NUMBER\"\n"));
        def build = job.scheduleBuild2(0, new Cause.UserCause()).get();
        assert Result.SUCCESS == build.result;
        File lastSuccessful = new File(job.rootDir, "lastSuccessful"),
             lastStable = new File(job.rootDir, "lastStable");
        // First build creates links
        assertSymlinkForBuild(lastSuccessful, 1);
        assertSymlinkForBuild(lastStable, 1);
        // Archive artifacts that don't exist to create failure in post-build action
        job.publishersList.add(new ArtifactArchiver("*.foo", "", false, false));
        build = job.scheduleBuild2(0, new Cause.UserCause()).get();
        assert Result.FAILURE == build.getResult();
        // Links should not be updated since build failed
        assertSymlinkForBuild(lastSuccessful, 1);
        assertSymlinkForBuild(lastStable, 1);
    }

    /* TODO too slow, seems capable of causing testWorkspaceLock to time out:
    @Test
    @Issue("JENKINS-15156")
    public void testGetBuildAfterGC() {
        FreeStyleProject job = j.createFreeStyleProject();
        job.scheduleBuild2(0, new Cause.UserIdCause()).get();
        j.jenkins.queue.clearLeftItems();
        MemoryAssert.assertGC(new WeakReference(job.getLastBuild()));
        assert job.lastBuild != null;
    }
    */

    @Test
    @Issue("JENKINS-17137")
    public void testExternalBuildDirectorySymlinks() {
        Assume.assumeFalse(Functions.isWindows()); // symlinks may not be available
        def form = j.createWebClient().goTo("configure").getFormByName("config");
        def builds = j.createTmpDir();
        form.getInputByName("_.rawBuildsDir").valueAttribute = builds.toString() + "/\${ITEM_FULL_NAME}";
        j.submit(form);
        assert builds.toString() + "/\${ITEM_FULL_NAME}" == j.jenkins.getRawBuildsDir();
        def p = j.jenkins.createProject(MockFolder.class, "d").createProject(FreeStyleProject.class, "p");
        def b1 = p.scheduleBuild2(0).get();
        def link = new File(p.rootDir, "lastStable");
        assert link.exists();
        assert b1.rootDir.absolutePath == resolveAll(link).absolutePath;
        def b2 = p.scheduleBuild2(0).get();
        assert link.exists();
        assert b2.rootDir.absolutePath == resolveAll(link).absolutePath;
        b2.delete();
        assert link.exists();
        assert b1.rootDir.absolutePath == resolveAll(link).absolutePath;
        b1.delete();
        assert !link.exists();
    }

    private File resolveAll(File link) throws InterruptedException, IOException {
        while (true) {
            File f = Util.resolveSymlinkToFile(link);
            if (f==null)    return link;
            link = f;
        }
    }

    @Test
    @Issue("JENKINS-17138")
    public void testExternalBuildDirectoryRenameDelete() {
        def form = j.createWebClient().goTo("configure").getFormByName("config");
        def builds = j.createTmpDir();
        form.getInputByName("_.rawBuildsDir").setValueAttribute(builds.toString() + "/\${ITEM_FULL_NAME}");
        j.submit(form);
        assert builds.toString() + "/\${ITEM_FULL_NAME}" == j.jenkins.rawBuildsDir;
        def p = j.jenkins.createProject(MockFolder.class, "d").createProject(FreeStyleProject.class, "prj");
        def b = p.scheduleBuild2(0).get();
        def oldBuildDir = new File(builds, "d/prj");
        assert new File(oldBuildDir, b.id) == b.rootDir;
        assert b.getRootDir().isDirectory();
        p.renameTo("proj");
        def newBuildDir = new File(builds, "d/proj");
        assert new File(newBuildDir, b.id) == b.rootDir;
        assert b.rootDir.isDirectory();
        p.delete();
        assert !b.rootDir.isDirectory();
    }

    @Test
    @Issue("JENKINS-18678")
    public void testRenameJobLostBuilds() throws Exception {
        def p = j.createFreeStyleProject("initial");
        j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        assertEquals(1, p.getBuilds().size());
        p.renameTo("edited");
        p._getRuns().purgeCache();
        assertEquals(1, p.getBuilds().size());
        def d = j.jenkins.createProject(MockFolder.class, "d");
        Items.move(p, d);
        assertEquals(p, j.jenkins.getItemByFullName("d/edited"));
        p._getRuns().purgeCache();
        assertEquals(1, p.getBuilds().size());
        d.renameTo("d2");
        p = j.jenkins.getItemByFullName("d2/edited");
        p._getRuns().purgeCache();
        assertEquals(1, p.getBuilds().size());
    }

    @Test
    @Issue("JENKINS-17575")
    public void testDeleteRedirect() {
        j.createFreeStyleProject("j1");
        assert "" == deleteRedirectTarget("job/j1");
        j.createFreeStyleProject("j2");
        Jenkins.getInstance().addView(new AllView("v1"));
        assert "view/v1/" == deleteRedirectTarget("view/v1/job/j2");
        MockFolder d = Jenkins.getInstance().createProject(MockFolder.class, "d");
        d.addView(new AllView("v2"));
        ["j3","j4","j5"].each { n -> d.createProject(FreeStyleProject.class, n) }
        assert "job/d/" == deleteRedirectTarget("job/d/job/j3");
        assert "job/d/view/v2/" == deleteRedirectTarget("job/d/view/v2/job/j4");
        assert "view/v1/job/d/" == deleteRedirectTarget("view/v1/job/d/job/j5");
        assert "view/v1/" == deleteRedirectTarget("view/v1/job/d"); // JENKINS-23375
    }

    private String deleteRedirectTarget(String job) {
        def wc = j.createWebClient();
        String base = wc.getContextPath();
        String loc = wc.getPage(wc.addCrumb(new WebRequest(new URL(base + job + "/doDelete"), HttpMethod.POST))).getUrl().toString();
        assert loc.startsWith(base): loc;
        return loc.substring(base.length());
    }

    @Test
    @Issue("JENKINS-18407")
    public void testQueueSuccessBehavior() {
        // prevent any builds to test the behaviour
        j.jenkins.numExecutors = 0;

        def p = j.createFreeStyleProject()
        def f = p.scheduleBuild2(0)
        assert f!=null;
        def g = p.scheduleBuild2(0)
        assert f==g;

        p.makeDisabled(true)
        assert p.scheduleBuild2(0)==null
    }

    /**
     * Do the same as {@link #testQueueSuccessBehavior()} but over HTTP
     */
    @Test
    @Issue("JENKINS-18407")
    public void testQueueSuccessBehaviorOverHTTP() {
        // prevent any builds to test the behaviour
        j.jenkins.numExecutors = 0;

        def p = j.createFreeStyleProject()
        def wc = j.createWebClient();

        def rsp = wc.getPage("${j.getURL()}${p.url}build").webResponse
        assert rsp.statusCode==201;
        assert rsp.getResponseHeaderValue("Location")!=null;

        def rsp2 = wc.getPage("${j.getURL()}${p.url}build").webResponse
        assert rsp2.statusCode==201;
        assert rsp.getResponseHeaderValue("Location")==rsp2.getResponseHeaderValue("Location")

        p.makeDisabled(true)

        try {
            wc.getPage("${j.getURL()}${p.url}build")
            fail();
        } catch (FailingHttpStatusCodeException e) {
            // request should fail
        }
    }

    /**
     * We used to store {@link AbstractProject#triggers} as {@link Vector}, so make sure
     * we can still read back the configuration from that.
     */
    @Test
    public void testVectorTriggers() {
        AbstractProject p = j.jenkins.createProjectFromXML("foo", getClass().getResourceAsStream("AbstractProjectTest/vectorTriggers.xml"))
        assert p.triggers().size()==1
        def t = p.triggers()[0]
        assert t.class==SCMTrigger.class;
        assert t.spec=="*/10 * * * *"
    }

    @Test
    @Issue("JENKINS-18813")
    public void testRemoveTrigger() {
        AbstractProject p = j.jenkins.createProjectFromXML("foo", getClass().getResourceAsStream("AbstractProjectTest/vectorTriggers.xml"))

        TriggerDescriptor SCM_TRIGGER_DESCRIPTOR = j.jenkins.getDescriptorOrDie(SCMTrigger.class)
        p.removeTrigger(SCM_TRIGGER_DESCRIPTOR);
        assert p.triggers().size()==0
    }

    @Test
    @Issue("JENKINS-18813")
    public void testAddTriggerSameType() {
        AbstractProject p = j.jenkins.createProjectFromXML("foo", getClass().getResourceAsStream("AbstractProjectTest/vectorTriggers.xml"))

        def newTrigger = new SCMTrigger("H/5 * * * *")
        p.addTrigger(newTrigger);

        assert p.triggers().size()==1
        def t = p.triggers()[0]
        assert t.class==SCMTrigger.class;
        assert t.spec=="H/5 * * * *"
    }

    @Test
    @Issue("JENKINS-18813")
    public void testAddTriggerDifferentType() {
        AbstractProject p = j.jenkins.createProjectFromXML("foo", getClass().getResourceAsStream("AbstractProjectTest/vectorTriggers.xml"))

        def newTrigger = new TimerTrigger("20 * * * *")
        p.addTrigger(newTrigger);

        assert p.triggers().size()==2
        def t = p.triggers()[1]
        assert t == newTrigger
    }

    @Test
    @Issue("JENKINS-10615")
    public void testWorkspaceLock() {
        def p = j.createFreeStyleProject()
        p.concurrentBuild = true;
        def e1 = new OneShotEvent(), e2=new OneShotEvent()
        def done = new OneShotEvent()

        p.publishersList.add(new Recorder() {
            BuildStepMonitor getRequiredMonitorService() {
                return BuildStepMonitor.NONE;
            }

            @Override
            boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
                if (build.number==1) {
                    e1.signal();  // signal that build #1 is in publisher
                } else {
                    assert build.number==2;
                    e2.signal()
                }

                done.block()

                return true;
            }
            private Object writeReplace() { return new Object(); }
        })

        def b1 = p.scheduleBuild2(0)
        e1.block()

        def b2 = p.scheduleBuild2(0)
        e2.block()

        // at this point both builds are in the publisher, so we verify that
        // the workspace are differently allocated
        assert b1.startCondition.get().workspace!=b2.startCondition.get().workspace

        done.signal()
    }

    /**
     * Trying to POST to config.xml by a different job type should fail.
     */
    @Test
    public void testConfigDotXmlSubmissionToDifferentType() {
        j.jenkins.pluginManager.installDetachedPlugin("javadoc")
        j.jenkins.pluginManager.installDetachedPlugin("junit")
        j.jenkins.pluginManager.installDetachedPlugin("display-url-api")
        j.jenkins.pluginManager.installDetachedPlugin("mailer")
        j.jenkins.pluginManager.installDetachedPlugin("maven-plugin")

        j.jenkins.crumbIssuer = null
        def p = j.createFreeStyleProject()

        HttpURLConnection con = postConfigDotXml(p, "<maven2-moduleset />")

        // this should fail with a type mismatch error
        // the error message should report both what was submitted and what was expected
        assert con.responseCode == 500
        def msg = con.errorStream.text
        println msg
        assert msg.contains(FreeStyleProject.class.name)
        assert msg.contains(MavenModuleSet.class.name)

        // control. this should work
        con = postConfigDotXml(p, "<project />")
        assert con.responseCode == 200
    }

    private HttpURLConnection postConfigDotXml(FreeStyleProject p, String xml) {
        HttpURLConnection con = new URL(j.getURL(), "job/${p.name}/config.xml").openConnection()
        con.requestMethod = "POST"
        con.setRequestProperty("Content-Type", "application/xml")
        con.doOutput = true
        con.outputStream.withStream { s ->
            s.write(xml.bytes)
        }
        return con
    }

    @Test
    @Issue("JENKINS-27549")
    public void testLoadingWithNPEOnTriggerStart() {
        AbstractProject project = j.jenkins.createProjectFromXML("foo", getClass().getResourceAsStream("AbstractProjectTest/npeTrigger.xml"))

        assert project.triggers().size() == 1
    }

    @Test
    @Issue("JENKINS-30742")
    public void testResolveForCLI() {
        try {
            AbstractProject not_found = AbstractProject.resolveForCLI("never_created");
            fail("Exception should occur before!");
        } catch (CmdLineException e) {
            assert e.getMessage().contentEquals("No such job \u2018never_created\u2019 exists.");
        }

        AbstractProject project = j.jenkins.createProject(FreeStyleProject.class, "never_created");
        try {
            AbstractProject not_found = AbstractProject.resolveForCLI("never_created1");
            fail("Exception should occur before!");
        } catch (CmdLineException e) {
            assert e.getMessage().contentEquals("No such job \u2018never_created1\u2019 exists. Perhaps you meant \u2018never_created\u2019?")
        }

    }

    static class MockBuildTriggerThrowsNPEOnStart extends Trigger<Item> {
        @Override
        public void start(hudson.model.Item project, boolean newInstance) { throw new NullPointerException(); }

        @Override
        public TriggerDescriptor getDescriptor() {
            return DESCRIPTOR;
        }

        public static final TriggerDescriptor DESCRIPTOR = new DescriptorImpl()

        @TestExtension("testLoadingWithNPEOnTriggerStart")
        static class DescriptorImpl extends TriggerDescriptor {

            public boolean isApplicable(hudson.model.Item item) {
                return false;
            }
        }
    }
}
