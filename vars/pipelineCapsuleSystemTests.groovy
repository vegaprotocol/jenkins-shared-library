void call() {
  println('pipelineCapsuleSystemTests params: ' + params)
  pipeline {
    agent none
    stages {
      stage('List test directories') {
        agent any
        steps {
          gitClone([
            url: 'git@github.com:vegaprotocol/system-tests.git',
            branch: params.SYSTEM_TESTS_BRANCH,
            credentialsId: 'vega-ci-bot',
            timeout: 2,
          ])
          testDirs = sh (
            script: "find tests -maxdepth 1 -type d | grep 'tests/'",
            returnStdout: true
          ).trim().split('\n').findAll{ it }
          // split into two arrays of half size of the list
          suits = testDirs.collate(dirs.size() / 2 + 1)
        }
      }
      stage('Call tests') {
        parallel {
          stage('Suit 1') {
            steps {
              build(
                job: 'common/system-tests-wrapper',
                // exclude
                parameters: [string(name: 'SYSTEM_TESTS_TEST_DIRECTORY', value: suits[0].join(" "))] + collectParams(),
              )
            }
          }
          stage('Suit 2'){
            steps{
              build(
                job: 'common/system-tests-wrapper',
                parameters: [string(name: 'SYSTEM_TESTS_TEST_DIRECTORY', value: suits[1].join(" "))] + collectParams(),
              )
            }
          }
        }
      }
    }
  }
}

