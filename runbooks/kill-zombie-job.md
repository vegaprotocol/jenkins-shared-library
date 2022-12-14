<!-- source: https://stackoverflow.com/questions/14456592/how-to-stop-an-unstoppable-zombie-job-on-jenkins-without-restarting-the-server -->
1. go to [script console](https://jenkins.ops.vega.xyz/manage/script)
2. run following script by replacing `jobName` and `jobNumber`:

```groovy
def jobName = "private/Snapshots/Stagnet1" // get from the browser URL and remove '/job' parts
def jobNumber = 4092 // needs to be integer
Jenkins.instance.getItemByFullName(jobName)
    .getBuildByNumber(jobNumber)
    .finish(hudson.model.Result.ABORTED, new java.io.IOException("Aborting build"));
```

3. go to aws console and make sure that zombie jobs didn't leave any zombie machines that burn unnecessary money