<?php
/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

namespace Application\Controller;

use Zend\View\Model\ViewModel;
use Application\Entity\Verification;
use Application\Pp\Catalog;
use Zend\Session\Container;
use Zend\Mail;
use HTMLPurifier;
use HTMLPurifier_Config;

class VerificationController extends AuthenticatedController {

    private $_nbVersionPluginVersionRepository;
    private $_verificationRepository;
    /**
     * @var \Application\Repository\UserRepository
     */
    private $_userRepository;
    private $_verificationRequestRepository;
    private $_pluginVersionRepository;
    private $_nbVersionRepository;

    public function __construct($nbVersionPluginVersionRepo, $verificationRepo, 
                                $userRepository, $verificationRequestRepository, $config,
                                $pluginVersionRepository, $nbVersionRepository) {
        parent::__construct($config);
        $this->_nbVersionPluginVersionRepository = $nbVersionPluginVersionRepo;
        $this->_verificationRepository = $verificationRepo;
        $this->_userRepository = $userRepository;
        $this->_verificationRequestRepository = $verificationRequestRepository;
        $this->_pluginVersionRepository = $pluginVersionRepository;
        $this->_nbVersionRepository = $nbVersionRepository;
    } 

    public function listAction() {
        return new ViewModel([
            'verificationRequests' => $this->_verificationRequestRepository->getVerificationRequestsForVerifier($this->getAuthenticatedUserId()),
            'isAdmin' => $this->isAdmin(),
        ]);
    }

    public function voteGoAction() {
        return $this->_handleVote(\Application\Entity\VerificationRequest::VOTE_GO);
    }

    public function voteNoGoAction() {
        return $this->_handleVote(\Application\Entity\VerificationRequest::VOTE_NOGO);
    }

    public function voteUndecidedAction() {
        return $this->_handleVote(\Application\Entity\VerificationRequest::VOTE_UNDECIDED);
    }

    private function _handleVote($vote) {
        $reqId = $this->params()->fromQuery('id');
        $bailOut = false;
        if (empty($reqId)) {
            $bailOut = true;
        }
        $req = $this->_verificationRequestRepository->find($reqId);
        if (!$req || $req->getVerifier()->getId() !== $this->getAuthenticatedUserId()) {
            $bailOut = true;
        }
        if ($bailOut) {
            return $this->redirect()->toRoute('verification', array(
                'action' => 'list'
            ));
        }
        $req->setVote($vote);
        $req->setVotedAt(new \DateTime('now'));
        $comment = $this->params()->fromPost('comment');
        if (!empty($comment)) {
            $config = HTMLPurifier_Config::createDefault();
            $purifier = new HTMLPurifier($config);
            $comment = $purifier->purify($comment);
            $req->setComment($comment);
        }
        $this->_verificationRequestRepository->persist($req);
        $votesBreakdown = $this->_verificationRequestRepository->getVotesBreakdownForVerification($req->getVerification()->getid());
        $verification = $req->getVerification();
        $verification->resolveStatus($votesBreakdown);
        $this->_verificationRepository->persist($verification);
        if ($verification->getStatus() == \Application\Entity\Verification::STATUS_NOGO) {
            $this->_sendNoGoNotification($req->getVerification(), $comment);
        } elseif ($verification->getStatus() == \Application\Entity\Verification::STATUS_GO) {
            $this->_sendGoNotification($req->getVerification(), $comment);
            $nbVersion = $verification->getNbVersionPluginVersion()->getNbVersion();
            $nbVersion->requestCatalogRebuild();
            $this->_nbVersionRepository->persist($nbVersion);
        }
        $this->flashMessenger()->setNamespace('success')->addMessage('Vote cast');
        return $this->redirect()->toRoute('verification', array(
            'action' => 'list'
        ));
    }

    public function voteMasterGoAction() {
        return $this->_handleMasterVote(\Application\Entity\Verification::STATUS_GO);
    }

    public function voteMasterNoGoAction() {
        return $this->_handleMasterVote(\Application\Entity\Verification::STATUS_NOGO);
    }

    private function _handleMasterVote($vote) {
        $verId = $this->params()->fromQuery('id');
        $bailOut = false;
        if (empty($verId) || !$this->isAdmin()) {
            $bailOut = true;
        }
        $ver = $this->_verificationRepository->find($verId);
        if (!$ver) {
            $bailOut = true;
        }
        if ($bailOut) {
            return $this->redirect()->toRoute('verification', array(
                'action' => 'list'
            ));
        }
        $ver->setStatus($vote);
        $comment = $this->params()->fromPost('comment');
        if (!empty($comment) && $vote == \Application\Entity\Verification::STATUS_NOGO) {
            $config = HTMLPurifier_Config::createDefault();
            $purifier = new HTMLPurifier($config);
            $comment = $purifier->purify($comment);
            $this->_sendNoGoNotification($ver, $comment);
        }
        $this->_verificationRepository->persist($ver);
        // delete related requests 
        $this->_verificationRequestRepository->deleteRequestsOfVerification($ver->getId());        
        $this->flashMessenger()->setNamespace('success')->addMessage('Master vote cast');
        return $this->redirect()->toRoute('verification', array(
            'action' => 'list'
        ));
    }

    public function createAction() {
        $bailOut = false;
        $nbvPvId = $this->params()->fromQuery('nbvPvId');
        if (empty($nbvPvId)) {
            $bailOut = true;
        }
        /** @var \Application\Entity\NbVersionPluginVersion $nbVersionPluginVersion   */
        $nbVersionPluginVersion = $this->_nbVersionPluginVersionRepository->find($nbvPvId);
        if (!$nbVersionPluginVersion) {
            $bailOut = true;
        }
        $plugin = $nbVersionPluginVersion->getPluginVersion()->getPlugin();
        if (!$plugin->isOwnedBy($this->getAuthenticatedUserId()) && !$this->isAdmin()) {
            $bailOut = true;
        }
        if ($bailOut) {
            return $this->redirect()->toRoute('plugin', array(
                'action' => 'list'
            ));
        }
        // create verification
        $verification = new Verification();
        $verification->setStatus(Verification::STATUS_REQUESTED);
        $verification->setCreatedAt(new \DateTime('now'));
        $verification->setPluginVersionId($nbvPvId);
        $this->_verificationRepository->persist($verification);
        // join it to nbVersionPluginVersion
        $nbVersionPluginVersion->setVerification($verification);
        $this->_nbVersionPluginVersionRepository->persist($nbVersionPluginVersion);
        $verification->setNbVersionPluginVersion($nbVersionPluginVersion);
        // generate requests for all verifiers
        $verifiers = $this->_userRepository->findVerifier();
        $verification->createRequests($verifiers, $plugin);
        $this->_verificationRepository->persist($verification);
        foreach($verification->getVerificationRequests() as $req) {
            $req->sendVerificationMail($plugin);
        }
        $this->flashMessenger()->setNamespace('success')->addMessage('Verification Requested.');
        return $this->redirect()->toUrl('../plugin-version/edit?id='.$nbVersionPluginVersion->getPluginVersion()->getId());         
    }

    private function _sendNoGoNotification($verification, $comment) {
        $plugin = $verification->getNbVersionPluginVersion()->getPluginVersion()->getPlugin();
        $nbVersion = $verification->getNbVersionPluginVersion()->getNbVersion()->getVersion();
        $pluginVersion = $verification->getNbVersionPluginVersion()->getPluginVersion()->getVersion();
        $mail = new Mail\Message();
        foreach($plugin->getAuthors() as $user) {
            $mail->addTo($user->getEmail());
        }
        $mail->setFrom('webmaster@netbeans.apache.org', 'NetBeans webmaster');
        $mail->setSubject('Verification of your '.$plugin->getName().' is complete');
        $mail->setBody('Hello plugin owner,

this is to inform you that publishing of your plugin on the Plugin Portal Update Center was NOT approved. Please read comments from plugin verifiers below, if any.

Plugin: '.$plugin->getName().'
NetBeans version: '.$nbVersion.'
Verification status: NOGO
Comments: '.$comment.'

Do not give up though. Address the comment(s), upload new binary of your plugin and request the verification again!

Good luck!
NetBeans development team

P.S.: This is an automatic email. DO NOT REPLY to this email.');
        $transport = new Mail\Transport\Sendmail();
        $transport->send($mail);
        // die(var_dump($mail->getBody()));
    }

    private function _sendGoNotification($verification, $comment) {
        /* @var $plugin Application\Entity\Plugin */
        $plugin = $verification->getNbVersionPluginVersion()->getPluginVersion()->getPlugin();
        $nbVersion = $verification->getNbVersionPluginVersion()->getNbVersion()->getVersion();
        $pluginVersion = $verification->getNbVersionPluginVersion()->getPluginVersion()->getVersion();
        $mail = new Mail\Message();
        foreach($plugin->getAuthors() as $user) {
            $mail->addTo($user->getEmail());
        }
        $mail->setFrom('webmaster@netbeans.apache.org', 'NetBeans webmaster');
        $mail->setSubject('Verification of your '.$plugin->getName().' is complete');
        $mail->setBody('Hello plugin owner,

this is to inform you that publishing of your plugin on the Plugin Portal Update Center was approved.

Plugin: '.$plugin->getName().'
NetBeans version: '.$nbVersion.'
Verification status: GO

Your plugin should be already available on the Plugin Portal Update Center by now:

https://plugins.netbeans.apache.org/data/'.$nbVersion.'/catalog.xml.gz

Thanks for your contribution!
NetBeans development team

P.S.: This is an automatic email. DO NOT REPLY to this email.');
        $transport = new Mail\Transport\Sendmail();
        $transport->send($mail);
        // die(var_dump($mail->getBody()));
    }
}
