@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.7')

import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import groovyx.net.http.URIBuilder


Map<String, String> getJobInfo() {
    //
    // Find build not triggered by upstream build
    //
    RunWrapper triggerBuild = null
    allBuilds = [currentBuild] + currentBuild.upstreamBuilds
    print("allBuilds=${allBuilds}")
    for (int i=0; i<allBuilds.size(); i++) {
        // TODO: remove extra logging at some point
        print("allBuilds[${i}].getProjectName=${allBuilds[i].getProjectName()}")
        print("allBuilds[${i}].getDescription=${allBuilds[i].getDescription()}")
        print("allBuilds[${i}].getDisplayName=${allBuilds[i].getDisplayName()}")
        print("allBuilds[${i}].getBuildCauses=${allBuilds[i].getBuildCauses()}")
        print("allBuilds[${i}].getFullDisplayName=${allBuilds[i].getFullDisplayName()}")
        print("allBuilds[${i}].getFullProjectName=${allBuilds[i].getFullProjectName()}")

        if (allBuilds[i].getBuildCauses('org.jenkinsci.plugins.workflow.support.steps.build.BuildUpstreamCause').isEmpty()) {
            // not triggered by upstream build
            triggerBuild = allBuilds[i]
            break
        }
    }
    if (triggerBuild == null) {
        print("SOMETHING IS WRONG - could not find trigger build")
        triggerBuild = currentBuild
    }
    //
    // Find who started the build
    //
    String started_by = ""
    String started_by_user = ""

    if (!triggerBuild.getBuildCauses('hudson.model.Cause$UserIdCause').isEmpty()) {
        started_by = "user"
        started_by_user = triggerBuild.getBuildCauses('hudson.model.Cause$UserIdCause').userName
    }
    if (!triggerBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause').isEmpty()) {
        started_by = "cron"
    }
    if (!triggerBuild.getBuildCauses('jenkins.branch.BranchEventCause').isEmpty()) {
        started_by = "commit"
    }
    //
    // Get PR info if started by PR
    //
    String pr = ""
    String pr_job_number = ""
    String pr_repo = ""
    if (triggerBuild.getProjectName().startsWith("PR-")) {
        pr = triggerBuild.getProjectName()
        pr_job_number = triggerBuild.getNumber()
        pr_repo = triggerBuild.getFullProjectName().split("/")[-2]
    }
    //
    // Get Job name
    //
    String job_name = currentBuild.getProjectName()
    String job_url = currentBuild.getAbsoluteUrl()
    if (job_name.startsWith("PR-")) {
        job_name = currentBuild.getFullProjectName().split("/")[-2]
    }
    String build_number = "${currentBuild.number}"
    return [
        agent: "${env.NODE_NAME}",
        build: triggerBuild,
        build_number: build_number,
        job_name: job_name,
        job_url: job_url,
        pr: pr,
        pr_job_number: pr_job_number,
        pr_repo: pr_repo,
        started_by: started_by,
        started_by_user: started_by_user,
    ]
}

String getNicePrefixForJobDescription() {
    String description = ""
    def jobInfo = getJobInfo()
    if (jobInfo.pr) {
        description += "[${jobInfo.pr_repo}/${jobInfo.pr} (${jobInfo.pr_job_number})]"
    } else if (jobInfo.started_by_user) {
        description += "${jobInfo.started_by_user}"
    } else {
        description += "[${jobInfo.started_by}]"
    }
    return description
}

String getMonitoringDashboardURL(Map<String, String> extraVars=[:]) {
    def monitoring_url = new URIBuilder("https://monitoring.vega.community")
    monitoring_url.setPath("/d/system-tests")
    def jobInfo = getJobInfo()
    if (extraVars?.agent) {
        monitoring_url.addQueryParam("var-agent", extraVars.agent)
    }
    if (extraVars?.job) {
        monitoring_url.addQueryParam("var-job", extraVars.job)
    } else if(jobInfo?.job_name) {
        monitoring_url.addQueryParam("var-job", extraVars.job_name)
    }
    if (extraVars?.test_mark) {
        monitoring_url.addQueryParam("var-test_mark", extraVars.test_mark)
    }
    if (extraVars?.test_directory) {
        monitoring_url.addQueryParam("var-test_directory", extraVars.test_directory)
    }
    if (jobInfo?.pr) {
        monitoring_url.addQueryParam("var-pr", extraVars.pr)
    }
    if (jobInfo?.pr_job_number) {
        monitoring_url.addQueryParam("var-pr_job_number", extraVars.pr_job_number)
    }
    if (jobInfo?.pr_repo) {
        monitoring_url.addQueryParam("var-pr_repo", extraVars.pr_repo)
    }
    if (jobInfo?.build_number) {
        monitoring_url.addQueryParam("var-build_number", extraVars.build_number)
    }
    if (jobInfo?.started_by_user) {
        monitoring_url.addQueryParam("var-started_by_user", extraVars.started_by_user)
    }
    // TODO ADD start from=1696896000000
    // TODO ADD end to=1696982399000
    return monitoring_url.toString()
}
