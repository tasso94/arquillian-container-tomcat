/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.arquillian.container.tomcat.managed.bean;

import org.camunda.bpm.BpmPlatform;
import org.camunda.bpm.ProcessEngineService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.impl.ProcessEngineImpl;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(Arquillian.class)
public class AccessBeanIT {

    protected ProcessEngineService processEngineService;
    protected ProcessEngine processEngine;
    protected ProcessEngineConfigurationImpl processEngineConfiguration;
    protected ManagementService managementService;
    protected RuntimeService runtimeService;

    @Before
    public void setupBeforeTest() {
        processEngineService = BpmPlatform.getProcessEngineService();
        processEngine = processEngineService.getDefaultProcessEngine();
        processEngineConfiguration = ((ProcessEngineImpl) processEngine).getProcessEngineConfiguration();
        processEngineConfiguration.getJobExecutor().shutdown(); // make sure the job executor is down
        managementService = processEngine.getManagementService();
        runtimeService = processEngine.getRuntimeService();
    }

    @Deployment
    public static WebArchive processArchive() {
        return ShrinkWrap.create(WebArchive.class, "test.war")
            .addAsWebInfResource("beans.xml", "beans.xml")
            .addAsResource("META-INF/processes.xml", "META-INF/processes.xml")
            .addClass(AccessBeanIT.class)
            .addAsManifestResource("context.xml")
            .addAsLibraries(Maven.configureResolver()
                .workOffline()
                .loadPomFromFile("pom.xml")
                .resolve("org.camunda.bpm:camunda-engine-cdi-jakarta", "org.jboss.weld.servlet:weld-servlet-shaded")
                .withoutTransitivity()
                .as(JavaArchive.class))
            .addClass(TestProcessApplication.class)
            .addAsWebInfResource("web.xml")
            .addAsResource("TimerRecalculation.bpmn20.xml")
            .addClass(TimerExpressionBean.class);
    }

    @Test
    public void shouldAccessVariableWithoutException() {
        runtimeService.startProcessInstanceByKey("TimerRecalculationProcess");
    }

}
