/*******************************************************************************
 * Copyright (c) 2018, 2019 IBM Corp. and others
 *
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which accompanies this
 * distribution and is available at https://www.eclipse.org/legal/epl-2.0/
 * or the Apache License, Version 2.0 which accompanies this distribution and
 * is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * This Source Code may also be made available under the following
 * Secondary Licenses when the conditions for such availability set
 * forth in the Eclipse Public License, v. 2.0 are satisfied: GNU
 * General Public License, version 2 with the GNU Classpath
 * Exception [1] and GNU General Public License, version 2 with the
 * OpenJDK Assembly Exception [2].
 *
 * [1] https://www.gnu.org/software/classpath/license.html
 * [2] http://openjdk.java.net/legal/assembly-exception.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0 OR GPL-2.0 WITH
 * Classpath-exception-2.0 OR LicenseRef-GPL-2.0 WITH Assembly-exception
 *******************************************************************************/

NAMESPACE = 'eclipse'
CONTAINER_NAME = 'openj9-docs'
ARCHIVE = 'doc.tar.gz'
HTTP = 'https://'
OPENJ9_REPO = 'github.com/eclipse/openj9-docs'
OPENJ9_STAGING_REPO = 'github.com/eclipse/openj9-docs-staging'
ECLIPSE_REPO = 'ssh://genie.openj9@git.eclipse.org:29418/www.eclipse.org/openj9/docs.git'

BUILD_DIR = 'built_doc'
CREDENTIAL_ID = 'b6987280-6402-458f-bdd6-7affc2e360d4'

switch (params.BUILD_TYPE) {
    case "MERGE":
        PUSH_REPO = OPENJ9_REPO
        PUSH_BRANCH = 'gh-pages'
        REFSPEC = "+refs/heads/*:refs/remotes/origin/*"
        // MERGE_COMMIT will be set by the webhook
        // unless launched manually, then we will need to figure it out
        if (params.MERGE_COMMIT) {
            CLONE_BRANCH = MERGE_COMMIT
            GET_SHA = false
        } else {
            CLONE_BRANCH = "refs/heads/master"
            GET_SHA = true
        }
        SERVER = 'Github'
        break
    case "PR":
        PUSH_REPO = OPENJ9_STAGING_REPO
        PUSH_BRANCH = 'master'
        REFSPEC = "+refs/pull/${ghprbPullId}/merge:refs/remotes/origin/pr/${ghprbPullId}/merge"
        GET_SHA = false
        CLONE_BRANCH = sha1
        MERGE_COMMIT = ghprbActualCommit
        break
    case "RELEASE":
        PUSH_REPO = ECLIPSE_REPO
        PUSH_BRANCH = 'master'
        REFSPEC = "+refs/heads/*:refs/remotes/origin/*"
        GET_SHA = true
        if (!params.RELEASE_TAG) {
            error("Must specify what release tag to push to Eclipse")
        }
        CLONE_BRANCH = "refs/tags/${RELEASE_TAG}"
        SERVER = 'Eclipse'
        break
    default:
        error("Unknown BUILD_TYPE:'${params.BUILD_TYPE}'")
        break
}

timeout(time: 6, unit: 'HOURS') {
    timestamps {
        try {
            node('hw.arch.x86&&sw.tool.docker') {
                def TMP_DESC = (currentBuild.description) ? currentBuild.description + "<br>" : ""
                currentBuild.description = TMP_DESC + "<a href=${JENKINS_URL}computer/${NODE_NAME}>${NODE_NAME}</a>"
                docker.image("${NAMESPACE}/${CONTAINER_NAME}:latest").inside {
                    stage('Build Doc') {
                        dir(BUILD_DIR) {
                            checkout changelog: false, poll: false,
                                scm: [$class: 'GitSCM',
                                    branches: [[name: CLONE_BRANCH]],
                                    userRemoteConfigs: [[refspec: REFSPEC, url: "${HTTP}${OPENJ9_REPO}"]]]

                            echo "here"
                            if (GET_SHA) {
                                echo "here1"
                                MERGE_COMMIT = sh (script: 'git rev-parse HEAD', returnStdout: true).trim()
                            }
                            echo "here2"
                            COMMIT_MSG = sh (script: 'git log -1 --oneline', returnStdout: true).trim()
                            echo "COMMIT_MSG:'${COMMIT_MSG}'"
                            echo "COMMIT_SHA:'${MERGE_COMMIT}'"

                            if (!params.ghprbPullId) {
                                // Set status on the Github commit for merge builds
                                setBuildStatus("${HTTP}${OPENJ9_REPO}", MERGE_COMMIT, 'PENDING', 'In Progress')
                                currentBuild.description += "<br>${COMMIT_MSG}"
                            }

                            // Need to insert website banners to identify the documentation
                            if (params.ghprbPullId) {
                                // Staging site banner
                                sh """
                                    sed -i 's|{% block hero %}|<!--Staging site notice --><div class="md-container"><div style="margin:-5rem 0 -5rem 0; background: linear-gradient(white, #69c1bd, white); color:white; text-align:center; font-size:1.5rem; font-weight:bold; text-shadow: 2px 2px 4px #000000;"><p style="padding:2rem 0 2rem 0;"><i class="fa fa-exclamation-triangle" aria-hidden="true"></i> CAUTION: This site is for reviewing draft documentation. For published content, visit <a style="color:#af6e3d; text-shadow:none; padding-left:1rem;" href="http://www.eclipse.org/openj9/docs/index.html">www.eclipse.org/openj9/docs</a></p></div>{% block hero %}|' theme/base.html
                                    """
                            }
                            else {
                                // Ghpages site banner
                                sh """
                                    sed -i 's|{% block hero %}|<!--Ghpages site notice --><div class="md-container"><div style="margin:-5rem 0 -5rem 0; background: linear-gradient(white, #ffa02e, white); color:white; text-align:center; font-size:1.5rem; font-weight:bold; text-shadow: 2px 2px 4px #000000;"><p style="padding:2rem 0 2rem 0;"><i class="fa fa-cogs" aria-hidden="true"></i> CAUTION: This site hosts draft documentation for the next release. For published content of the latest release, visit <a style="color:#af6e3d; text-shadow:none; padding-left:1rem;" href="http://www.eclipse.org/openj9/docs/index.html">www.eclipse.org/openj9/docs</a></p></div>{% block hero %}|' theme/base.html
                                    """
                            }

                            sh 'mkdocs build -v'
                        }
                    }
                        // If we're pushing to Github, no need to switch over to master node
                        if ((params.BUILD_TYPE == "PR") || (params.BUILD_TYPE == "MERGE")) {
                            stage("Push Doc") {
                                dir('push_repo') {
                                    git branch: PUSH_BRANCH, url: "${HTTP}${PUSH_REPO}"
                                    if (params.BUILD_TYPE == "PR") {
                                        dir("${ghprbPullId}") {
                                            copy_built_doc(BUILD_DIR)
                                            push_doc_with_cred(PUSH_REPO, PUSH_BRANCH, MERGE_COMMIT)
                                        }
                                    } else {
                                        copy_built_doc(BUILD_DIR)
                                        push_doc_with_cred(PUSH_REPO, PUSH_BRANCH, MERGE_COMMIT)
                                        // Set status on the Github commit for merge builds
                                        setBuildStatus("${HTTP}${OPENJ9_REPO}", MERGE_COMMIT, 'SUCCESS', "Doc built and pushed to ${SERVER} openj9-docs:${PUSH_BRANCH}")
                                    }
                                }
                            }
                        } else {
                            dir(BUILD_DIR) {
                                sh "tar -zcf ${ARCHIVE} site/"
                                stash includes: "${ARCHIVE}", name: 'doc'
                            }
                        }
                } // Exit container, no need to cleanWs()
            }

            if (params.BUILD_TYPE == "RELEASE") {
                node('master') {
                    stage('Push Doc') {
                        try {
                            dir(BUILD_DIR) {
                                unstash 'doc'
                                sh "tar -zxf ${ARCHIVE}"
                            }

                            dir('eclipse') {
                                git branch: PUSH_BRANCH, url: PUSH_REPO
                                copy_built_doc(BUILD_DIR)
                                push_doc(PUSH_REPO, PUSH_BRANCH, MERGE_COMMIT)
                            }

                            // Set status on the Github commit for merge builds
                            setBuildStatus("${HTTP}${OPENJ9_REPO}", MERGE_COMMIT, 'SUCCESS', "Doc built and pushed to ${SERVER} openj9-docs:${PUSH_BRANCH}")
                        } finally {
                            cleanWs()
                        }
                    }
                }
            }
        } catch(e) {
            if (!params.ghprbPullId) {
                node('worker') {
                    // Set status on the Github commit for merge builds
                    setBuildStatus("${HTTP}${OPENJ9_REPO}", MERGE_COMMIT, 'FAILURE', 'Failed to build and push doc')
                    slackSend channel: '#jenkins', color: 'danger', message: "Failed: ${env.JOB_NAME} #${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
                }
            }
            throw e
        }
    }
}

def copy_built_doc(DIR) {
    sh "rm -rf *"
    sh "cp -r ${WORKSPACE}/${DIR}/site/* ."
}

def push_doc_with_cred(REPO, BRANCH, SHA){
    withCredentials([usernamePassword(credentialsId: CREDENTIAL_ID, usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
        push_doc("${HTTP}${USERNAME}:${PASSWORD}@${REPO}", BRANCH, SHA)
    }
}
def push_doc(REPO, BRANCH, SHA) {
    sh "git status"
    STATUS = sh (script: 'git status --porcelain', returnStdout: true).trim()
    if ("x${STATUS}" != "x") {
        sh 'git config user.name "Eclipse OpenJ9 Bot"'
        sh 'git config user.email "openj9-bot@eclipse.org"'
        sh 'git add .'
        sh "git commit -m 'Generated from commit: ${SHA}'"
        echo "git push ${REPO} ${BRANCH}"
    }
}

def setBuildStatus(REPO, SHA, STATE, MESSAGE) {
    step([
        $class: "GitHubCommitStatusSetter",
        reposSource: [$class: "ManuallyEnteredRepositorySource", url: REPO],
        contextSource: [$class: "ManuallyEnteredCommitContextSource", context: JOB_NAME],
        errorHandlers: [[$class: "ChangingBuildStatusErrorHandler", result: "UNSTABLE"]],
        commitShaSource: [$class: "ManuallyEnteredShaSource", sha: SHA ],
        statusBackrefSource: [$class: "ManuallyEnteredBackrefSource", backref: BUILD_URL],
        statusResultSource: [$class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: MESSAGE, state: STATE]] ]
    ]);
}
