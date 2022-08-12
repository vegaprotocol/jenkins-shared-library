void call() {
  println('pipelineCapsuleSystemTests params: ' + params)
  wrapper = 'common/system-tests-wrapper'
  childs = []
  pipeline {
    agent none
    stages {
      stage('Call tests') {
        parallel {
          stage('h-z') {
            steps {
              script {
                childs.add(build(
                  job: wrapper,
                  parameters: collectParams() + [string(name: 'TEST_EXTRA_PYTEST_ARGS', value: "--collect-only --ignore-glob 'tests/[h-zH-Z]*/**.py'")],
                ))
              }
            }
          }
          stage('a-g + n-z') {
            steps {
              script {
                childs.add(build(
                  job: wrapper,
                  parameters: collectParams() + [string(name: 'TEST_EXTRA_PYTEST_ARGS', value: "--collect-only --ignore-glob 'tests/[a-gA-Gn-zN-Z]*/**.py'")],
                ))
              }
            }
          }
          stage('a-m') {
            steps {
              script {
                childs.add(build(
                  job: wrapper,
                  parameters: collectParams() + [string(name: 'TEST_EXTRA_PYTEST_ARGS', value: "--collect-only --ignore-glob 'tests/[a-mA-M]*/**.py'")],
                ))
              }
            }
          }
        }
      }
      stage('Collect results') {
        agent any
        steps {
          sh "mkdir results"
          script {
            childs.each {
              copyArtifacts(
                  filter : "build/test-reports/system-test-results.xml",
                  flatten: true,
                  projectName : wrapper,
                  // job object is in list, it's call for getNumber()
                  selector: buildParameter(it.number)
              )
              sh "mv system-tests-results.xml results/system-tests-results-${it.number}.xml"
            }
          }
          junit(
            checksName: 'System Tests',
            testResults: 'results/**.xml',
            skipMarkingBuildUnstable: false,
            skipPublishingChecks: false,
          )
        }
        post {
          always {
            cleanWs()
          }
        }
      }
    }
  }
}

