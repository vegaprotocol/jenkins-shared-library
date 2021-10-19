
String call() {
    /* groovylint-disable-next-line LineLength */
    String branchList = "'${env.BRANCH_NAME}' 'origin/${env.BRANCH_NAME}'"
    if (env.CHANGE_BRANCH) {
        branchList += " '${env.CHANGE_BRANCH}' 'origin/${env.CHANGE_BRANCH}'"
    }
    echo "BRANCH_NAME='${env.BRANCH_NAME}', CHANGE_BRANCH='${env.CHANGE_BRANCH}', branchList=\"${branchList}\""

    // Check current HEAD
    String commitHash = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
    echo "commitHash='${commitHash}'"
    String result = sh(returnStdout: true, script: "git branch -a ${branchList} --contains ${commitHash}").trim()
    echo "result='${result}'"
    if (result != '') {
        return commitHash
    }

    // Check tags for current HEAD
    result = sh(returnStdout: true, script: "git tag ${branchList} --contains ${commitHash}").trim()
    echo "result='${result}'"
    if (result != '') {
        return commitHash
    }


    // Check previous HEAD
    commitHash = sh(returnStdout: true, script: 'git rev-parse HEAD@{1}').trim()
    echo "next commitHash='${commitHash}'"
    result = sh(returnStdout: true, script: "git branch -a ${branchList} --contains ${commitHash}").trim()
    echo "next result='${result}'"
    if (result != '') {
        return commitHash
    }

    return error("Failed to find last commit for branches: \"${branchList}\"")
}
