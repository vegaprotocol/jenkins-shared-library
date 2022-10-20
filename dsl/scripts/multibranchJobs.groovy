// def createCommonMultibranchPipeline(Map args){
//     return multibranchPipelineJob(args.name) {
//         description(jobs.standardDescription())
//         branchSources {
//             branchSource {
//                 source {
//                     git {
//                         remote("git@github.com:vegaprotocol/.git")
//                         credentialsId('')
//                         id(generateUUIDForString(args.name))
//                         traits {
//                             cloneOptionTrait {
//                                 extension {
//                                     noTags(false)
//                                     shallow(false)
//                                     reference('')
//                                     timeout(10) //10 minutes for timeout of performing git clone
//                                 }
//                             }
//                         }
//                     }
//                 }
//                 buildStrategies {
//                     buildNamedBranches {
//                         filters {
//                             wildcards {
//                                 includes("*")
//                                 excludes("")
//                                 caseSensitive(false)
//                             }
//                         }
//                     }
//                 }
//             }
//         }
//         // Fix from https://issues.jenkins-ci.org/browse/JENKINS-46202
//         configure {
//             def traits = it / sources / data / 'jenkins.branch.BranchSource' / source / traits
//             traits << 'jenkins.plugins.git.traits.BranchDiscoveryTrait' {
//                 strategyId(1)
//             }
//             traits << 'jenkins.plugins.git.traits.TagDiscoveryTrait' {}
//         }
//     }
// }

// def multibranchJobs = [

// ]
