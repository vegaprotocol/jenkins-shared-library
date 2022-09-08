/* groovylint-disable DuplicateStringLiteral */

// Usage:
// script {
//   startVegaDevRelease vegaVersion: ${hash}
// }
void call(Map config = [:]) {
    echo 'Build and publish Vega Dev Releases to https://github.com/vegaprotocol/vega-dev-releases/releases'

    // docs: https://www.jenkins.io/doc/pipeline/steps/pipeline-build-step/
    build(
        job: 'private/Deployments/Vegavisor/Publish-vega-dev-releases',
        propagate: false,
        wait: false,
        parameters: [
            string(name: 'VEGA_VERSION', value: config.vegaVersion),
            string(name: 'JENKINS_SHARED_LIB_BRANCH', value: config.jenkinsSharedLib),
        ]
    )
}
