@NonCPS
List<String> call() {

    if (env.CHANGE_BRANCH) {
        return sh(
                script: "git diff --name-only origin/${env.CHANGE_TARGET}... --",
                returnStdout: true,
            ).trim().split('\n')
    }

    changedFiles = []
    for (changeLogSet in currentBuild.changeSets) { 
        for (entry in changeLogSet.getItems()) { // for each commit in the detected changes
            for (file in entry.getAffectedFiles()) {
                changedFiles.add(file.getPath()) // add changed file to list
            }
        }
    }

    return changedFiles

}
