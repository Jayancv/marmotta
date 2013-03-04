/*
 * Copyright (c) 2013 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.marmotta.kiwi.reasoner.test.parser;

import com.google.common.collect.ImmutableMap;
import org.apache.marmotta.kiwi.reasoner.model.program.Rule;
import org.apache.marmotta.kiwi.reasoner.parser.KWRLProgramParser;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openrdf.repository.Repository;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.sail.memory.MemoryStore;

/**
 * Test parsing of individual rules
 * <p/>
 * Author: Sebastian Schaffert (sschaffert@apache.org)
 */
public class KWRLRuleParserTest {

    private Repository repository;

    @Before
    public void setup() throws Exception {
        repository = new SailRepository(new MemoryStore());
        repository.initialize();
    }


    @After
    public void shutdown() throws Exception {
        repository.shutDown();
    }

    @Test
    public void testRule1() throws Exception {
        String rule = "($1 http://www.w3.org/2000/01/rdf-schema#subClassOf $2), ($2 http://www.w3.org/2000/01/rdf-schema#subClassOf $3) -> ($1 http://www.w3.org/2000/01/rdf-schema#subClassOf $3)";
        Rule r = KWRLProgramParser.parseRule(rule,repository.getValueFactory());

        Assert.assertNotNull(r);
        Assert.assertEquals(2, r.getBody().size());
        Assert.assertTrue(r.getHead().getSubject().isVariableField());
        Assert.assertTrue(r.getHead().getProperty().isResourceField());
        Assert.assertTrue(r.getHead().getObject().isVariableField());
    }

    @Test
    public void testRule2() throws Exception {
        String rule = "($1 rdfs:subClassOf $2), ($2 rdfs:subClassOf $3) -> ($1 rdfs:subClassOf $3)";
        Rule r = KWRLProgramParser.parseRule(rule, ImmutableMap.of("rdfs", "http://www.w3.org/2000/01/rdf-schema#"), repository.getValueFactory());

        Assert.assertNotNull(r);
        Assert.assertEquals(2, r.getBody().size());
        Assert.assertTrue(r.getHead().getSubject().isVariableField());
        Assert.assertTrue(r.getHead().getProperty().isResourceField());
        Assert.assertTrue(r.getHead().getObject().isVariableField());
    }

}