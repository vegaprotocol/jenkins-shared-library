// https://jenkins.ops.vega.xyz/plugin/job-dsl/api-viewer/index.html#path/multibranchPipelineJob
def createCommonMultibranchPipeline(Map args){
    return multibranchPipelineJob(args.name) {
        description(jobs.standardDescription())
        authorization {
            permission("hudson.model.Item.Read","Anonymous")
        }
        orphanedItemStrategy {
            discardOldItems {
                daysToKeep(14)
            }
        }
        triggers {
            periodicFolderTrigger {
                interval("10m")
            }
        }
        factory {
            workflowBranchProjectFactory {
                scriptPath("Jenkinsfile")
            }
        }
        branchSources {
            branchSource {
                source {
                    github {
                        repositoryUrl("https://github.com/vegaprotocol/${args.repoName ?: args.name}")
                        credentialsId('Vega Jenkins')
                        id(generateUUIDForString(args.name))
                        traits {
                            cloneOption {
                                extension {
                                    honorRefspec(true)
                                    noTags(false)
                                    timeout(3)
                                }
                            }
                            cleanAfterCheckout {
                                extension {
                                    deleteUntrackedNestedRepositories(true)
                                }
                            }
                            gitHubBranchDiscovery {
                                // 1 Exclude branches that are also filed as PRs
                                // 2 Only branches that are also filed as PRs
                                // 3 All branches
                                strategyId(3)
                            }
                            gitHubPullRequestDiscovery {
                                // 1 Merging the pull request with the current target branch revision
                                // 2 The current pull request revision
                                // 3 Both the current pull request revision and the pull request merged with the current target branch revision
                                strategyId(1)
                            }
                            gitTagDiscovery()
                        }
                    }
                }
                stragety {
                    allBranchesSame {

                    }
                }
                buildStrategies {
                    buildChangeRequests {
                        ignoreTargetOnlyChanges(true)
                        ignoreUntrustedChanges(true)
                    }
                    buildNamedBranches {
                        filters {
                            wildcards {
                                includes("develop main master")
                            }
                        }
                    }
                    buildTags {
                        atMostDays("3")
                    }
                }
            }
        }
    }
}

def multibranchJobs = [
    [
        name: 'vegacapsule-test',
        repoName: 'vegacapsule',
    ],
]

multibranchJobs.each {
    createCommonMultibranchPipeline(it)
}