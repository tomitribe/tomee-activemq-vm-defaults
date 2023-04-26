/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tomitribe.tomee.activemqvm;

import jakarta.annotation.Resource;
import jakarta.ejb.EJB;
import jakarta.ejb.Lock;
import jakarta.ejb.LockType;
import jakarta.ejb.Singleton;
import jakarta.ejb.embeddable.EJBContainer;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.activemq.broker.BrokerRegistry;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.TransportConnector;
import org.apache.commons.io.FileUtils;
import org.apache.tomee.embedded.EmbeddedTomEEContainer;
import org.apache.ziplock.IO;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class DefaultsTest {

    private static EJBContainer container;
    private static File webApp;
    private static int port;

    @BeforeClass
    public static void start() throws IOException {
        System.setProperty("openejb.provider.default", "org.tomitribe.activemq-vm");


        // get a random unused port to use for http requests
        ServerSocket server = new ServerSocket(0);
        port = server.getLocalPort();
        server.close();

        webApp = createWebApp();
        Properties p = new Properties();
        p.setProperty(EJBContainer.APP_NAME, "sample");
        p.setProperty(EJBContainer.PROVIDER, "tomee-embedded"); // need web feature
        p.setProperty(EJBContainer.MODULES, webApp.getAbsolutePath());
        p.setProperty(EmbeddedTomEEContainer.TOMEE_EJBCONTAINER_HTTP_PORT, String.valueOf(port));
        container = EJBContainer.createEJBContainer(p);
    }

    @AfterClass
    public static void stop() {
        if (container != null) {
            container.close();
        }
        if (webApp != null) {
            try {
                FileUtils.forceDelete(webApp);
            } catch (IOException e) {
                FileUtils.deleteQuietly(webApp);
            }
        }
    }

    private static File createWebApp() throws IOException {
        final File tempFile = File.createTempFile("sample-", ".war");

        final WebArchive webArchive = ShrinkWrap.create(WebArchive.class)
                .addClasses(MyService.class, MyServlet.class);

        ZipExporter exporter = webArchive.as(ZipExporter.class);
        exporter.exportTo(tempFile, true);

        return tempFile;
    }

    @Test
    public void test() throws Exception {
        final HttpURLConnection urlConnection = (HttpURLConnection) new URL("http://localhost:" + port + "/sample/").openConnection();
        urlConnection.connect();
        Assert.assertEquals(200, urlConnection.getResponseCode());
        final String slurp = IO.slurp(urlConnection.getInputStream());
        Assert.assertEquals("OK", slurp);
    }

    @Singleton
    @Lock(LockType.READ)
    public static class MyService {

        @Resource
        private ConnectionFactory cf;

        @Resource(name="MSG_QUEUE")
        private Queue queue;

        public void sendMessage() throws Exception {
            try (final Connection c = cf.createConnection();
                 final Session s = c.createSession(true, Session.AUTO_ACKNOWLEDGE);
                 final MessageProducer mp = s.createProducer(queue)) {

                final TextMessage tm = s.createTextMessage("Test Message!");
                mp.send(tm);
            }
        }
    }

    @WebServlet(urlPatterns = "/*")
    public static class MyServlet extends HttpServlet {

        @EJB
        private MyService myService;

        @Override
        protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
            try {
                // check we can send a message
                myService.sendMessage();

                final Map<String, BrokerService> brokers = BrokerRegistry.getInstance().getBrokers();

                // check there is 1 broker:
                Assert.assertEquals(1, brokers.size());

                // check that broker is "localhost"
                final BrokerService localhost = brokers.get("localhost");
                Assert.assertNotNull(localhost);

                // check that it only uses the VM:// transport
                final List<TransportConnector> transportConnectors = localhost.getTransportConnectors();
                Assert.assertEquals(1, transportConnectors.size());

                final TransportConnector transportConnector = transportConnectors.get(0);
                Assert.assertEquals(new URI("vm://localhost"), transportConnector.getConnectUri());

                final PrintWriter writer = resp.getWriter();
                writer.print("OK");
                writer.flush();
            } catch (Exception e) {
                throw new ServletException(e);
            }
        }
    }
}
