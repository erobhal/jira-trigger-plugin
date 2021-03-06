package com.ceilfors.jenkins.plugins.jiratrigger

import com.ceilfors.jenkins.plugins.jiratrigger.changelog.CustomFieldChangelogMatcher
import com.ceilfors.jenkins.plugins.jiratrigger.changelog.JiraFieldChangelogMatcher
import com.ceilfors.jenkins.plugins.jiratrigger.integration.JenkinsRunner
import com.ceilfors.jenkins.plugins.jiratrigger.integration.JulLogLevelRule
import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException
import hudson.model.AbstractBuild
import hudson.model.AbstractProject
import hudson.model.FreeStyleProject
import hudson.model.Queue
import hudson.security.GlobalMatrixAuthorizationStrategy
import hudson.security.HudsonPrivateSecurityRealm
import hudson.util.Secret
import jenkins.model.GlobalConfiguration
import org.acegisecurity.context.SecurityContextHolder
import org.junit.Rule
import org.junit.rules.RuleChain
import org.jvnet.hudson.test.Issue
import spock.lang.Specification

import static org.hamcrest.Matchers.isEmptyOrNullString
import static org.hamcrest.Matchers.not
import static org.junit.Assert.assertThat

/**
 * @author ceilfors
 */
class JiraTriggerIntegrationTest extends Specification {

    JenkinsRunner jenkins = new JenkinsRunner()

    @Rule
    RuleChain ruleChain = RuleChain
            .outerRule(new JulLogLevelRule())
            .around(jenkins)

    private AbstractBuild getScheduledBuild(AbstractProject project) {
        Queue.Item item = project.queueItem
        if (item == null) {
            project.getBuildByNumber(1)
        } else {
            item.future.get()
            (item.future.startCondition.get() as AbstractBuild)
        }
    }

    def 'Global configuration round trip'() {
        given:
        JiraTriggerGlobalConfiguration before = GlobalConfiguration.all().get(JiraTriggerGlobalConfiguration)
        before.jiraRootUrl = 'localhost:2990/jira'
        before.jiraUsername = 'admin'
        before.jiraPassword = Secret.fromString('admin')
        before.save()

        when:
        jenkins.configRoundtrip()

        then:
        JiraTriggerGlobalConfiguration after = GlobalConfiguration.all().get(JiraTriggerGlobalConfiguration)
        jenkins.assertEqualBeans(before, after, 'jiraRootUrl,jiraUsername,jiraPassword')
    }

    def 'JiraChangelogTrigger configuration round trip'() {
        given:
        FreeStyleProject p = jenkins.createFreeStyleProject()
        JiraChangelogTrigger before = new JiraChangelogTrigger()
        before.changelogMatchers = [
                new JiraFieldChangelogMatcher('status', 'new value', 'old value'),
                new CustomFieldChangelogMatcher('custom field', 'new value', 'old value'),
        ]
        p.addTrigger(before)
        p.save()

        when:
        jenkins.configRoundtrip()

        then:
        JiraChangelogTrigger after = p.getTrigger(JiraChangelogTrigger)
        jenkins.assertEqualBeans(before, after, 'changelogMatchers')
    }

    def 'Comment pattern by default must not be empty'() {
        when:
        def project = jenkins.createJiraCommentTriggeredProject('job')

        then:
        assertThat(project.jiraTrigger.commentPattern, not(isEmptyOrNullString()))
    }

    def 'Injects environment variable to scheduled build'() {
        given:
        jenkins.createJiraCommentTriggeredProject('job')
        jenkins.quietPeriod = 0

        when:
        def scheduledProjects = jenkins.jiraTriggerExecutor.scheduleBuilds(
                TestUtils.createIssue('TEST-1234'),
                TestUtils.createComment(JiraCommentTrigger.DEFAULT_COMMENT))

        then:
        scheduledProjects.size() != 0
        AbstractBuild build = getScheduledBuild(scheduledProjects[0])
        build.environment.get('JIRA_ISSUE_KEY') == 'TEST-1234'
    }

    @Issue('JENKINS-41878')
    def 'Should not serialize any Atlassian issue object into build.xml'() {
        given:
        jenkins.createJiraCommentTriggeredProject('job')
        jenkins.quietPeriod = 0

        when:
        def scheduledProjects = jenkins.jiraTriggerExecutor.scheduleBuilds(
                TestUtils.createIssue('TEST-1234'),
                TestUtils.createComment(JiraCommentTrigger.DEFAULT_COMMENT))

        then:
        scheduledProjects.size() != 0
        AbstractBuild build = getScheduledBuild(scheduledProjects[0])

        // This bug is testable this way because the serialization doesn't fail in our test and we cannot
        // reproduce the bug in the integration test easily.
        def buildXml = new File(build.rootDir, 'build.xml')
        buildXml.exists()
        !buildXml.text.contains('<issue>')
    }

    @Issue('JENKINS-34135')
    def 'Should be able to schedule jobs with anonymous user does not have read permission'() {
        setup:
        jenkins.createJiraCommentTriggeredProject('job')

        when: 'Security is enabled in Jenkins and anonymous access is taken off by empty authorization strategy'
        jenkins.instance.securityRealm = new HudsonPrivateSecurityRealm(true)
        jenkins.instance.authorizationStrategy = new GlobalMatrixAuthorizationStrategy()

        then: 'Item should still visible before we make the thread anonymous'
        jenkins.instance.allItems.size() == 1

        when: 'Thread is made anonymous'
        SecurityContextHolder.clearContext()

        then: 'Item should now disappear'
        jenkins.instance.allItems.size() == 0

        when: 'Trigger item'
        def scheduledProjects = jenkins.jiraTriggerExecutor.scheduleBuilds(
                TestUtils.createIssue('TEST-1234'),
                TestUtils.createComment(JiraCommentTrigger.DEFAULT_COMMENT))

        then: 'Item is scheduled'
        scheduledProjects.size() != 0
        scheduledProjects[0].queueItem.task.name == 'job'
    }

    @Issue('JENKINS-34301')
    def 'Should not swallow Jenkins security exception'() {
        setup:
        jenkins.createJiraCommentTriggeredProject('job')
        def webClient = jenkins.createWebClient()

        when: 'Security is enabled in Jenkins and anonymous access is taken off by empty authorization strategy'
        jenkins.instance.securityRealm = new HudsonPrivateSecurityRealm(true)
        jenkins.instance.authorizationStrategy = new GlobalMatrixAuthorizationStrategy()
        webClient.getPage(webClient.contextPath)

        then:
        FailingHttpStatusCodeException exception = thrown(FailingHttpStatusCodeException)
        exception.statusCode == 403
    }
}
