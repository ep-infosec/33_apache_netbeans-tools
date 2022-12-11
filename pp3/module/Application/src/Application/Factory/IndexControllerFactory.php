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

namespace Application\Factory;

use Zend\ServiceManager\ServiceLocatorInterface;
use Zend\ServiceManager\FactoryInterface;
use Application\Repository\PluginRepository;
use Application\Controller\IndexController;
use Application\Repository\NbVersionRepository;
use Application\Repository\CategoryRepository;
use Application\Repository\PluginVersionRepository;
use Application\Repository\VerificationRepository;

class IndexControllerFactory implements FactoryInterface
{
    public function createService(ServiceLocatorInterface $serviceLocator) {
        $em = $serviceLocator->getServiceLocator()->get('Doctrine\ORM\EntityManager');
        $repository = new PluginRepository();
        $repository->setEntityManager($em);
        $config = $serviceLocator->getServiceLocator()->get('config');
        $paginator = $serviceLocator->getServiceLocator()->get('paginator');

        $nbVersionRepository = new NbVersionRepository();
        $nbVersionRepository->setEntityManager($em);

        $categoryRepository = new CategoryRepository();
        $categoryRepository->setEntityManager($em);

        $pvRepository = new PluginVersionRepository();
        $pvRepository->setEntityManager($em);

        $verificationRepository = new VerificationRepository();
        $verificationRepository->setEntityManager($em);

        return new IndexController($repository, $config, $paginator, $nbVersionRepository, $categoryRepository, $pvRepository, $verificationRepository);
    }
}
