/*
 * Copyright 2015 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.async.client

import com.mongodb.Block
import com.mongodb.Function
import com.mongodb.MongoException
import com.mongodb.MongoNamespace
import com.mongodb.async.AsyncBatchCursor
import com.mongodb.async.FutureResultCallback
import com.mongodb.operation.DistinctOperation
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.Document
import org.bson.codecs.BsonValueCodecProvider
import org.bson.codecs.DocumentCodec
import org.bson.codecs.DocumentCodecProvider
import org.bson.codecs.ValueCodecProvider
import org.bson.codecs.configuration.CodecConfigurationException
import org.bson.codecs.configuration.RootCodecRegistry
import spock.lang.Specification

import static com.mongodb.CustomMatchers.isTheSameAs
import static com.mongodb.ReadPreference.secondary
import static java.util.Arrays.asList
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static spock.util.matcher.HamcrestSupport.expect

class DistinctIterableSpecification extends Specification {

    def namespace = new MongoNamespace('db', 'coll')
    def codecRegistry = new RootCodecRegistry([new ValueCodecProvider(),
                                               new DocumentCodecProvider(),
                                               new BsonValueCodecProvider()])
    def readPreference = secondary()

    def 'should build the expected DistinctOperation'() {
        given:
        def cursor = Stub(AsyncBatchCursor) {
            next(_) >> {
                it[0].onResult(null, null)
            }
        }
        def executor = new TestOperationExecutor([cursor, cursor]);
        def distinctIterable = new DistinctIterableImpl<Document>(namespace, Document, codecRegistry, readPreference, executor, 'field')

        when: 'default input should be as expected'
        distinctIterable.into([]) { result, t -> }

        def operation = executor.getReadOperation() as DistinctOperation<Document>
        def readPreference = executor.getReadPreference()

        then:
        expect operation, isTheSameAs(new DistinctOperation<Document>(namespace, 'field', new DocumentCodec()));
        readPreference == secondary()

        when: 'overriding initial options'
        distinctIterable.filter(new Document('field', 1)).maxTime(999, MILLISECONDS).batchSize(99).into([]) { result, t -> }

        operation = executor.getReadOperation() as DistinctOperation<Document>

        then: 'should use the overrides'
        expect operation, isTheSameAs(new DistinctOperation<Document>(namespace, 'field', new DocumentCodec())
                .filter(new BsonDocument('field', new BsonInt32(1)))
                .maxTime(999, MILLISECONDS))
    }

    def 'should handle exceptions correctly'() {
        given:
        def codecRegistry = new RootCodecRegistry(asList(new ValueCodecProvider(), new BsonValueCodecProvider()))
        def executor = new TestOperationExecutor([new MongoException('failure')])
        def distinctIterable = new DistinctIterableImpl(namespace, BsonDocument, codecRegistry, readPreference, executor, 'field')

        def futureResultCallback = new FutureResultCallback()

        when: 'The operation fails with an exception'
        distinctIterable.into([], futureResultCallback)
        futureResultCallback.get()

        then: 'the future should handle the exception'
        thrown(MongoException)


        when: 'a codec is missing'
        futureResultCallback = new FutureResultCallback<List<BsonDocument>>()
        distinctIterable.filter(new Document('field', 1)).into([], futureResultCallback)
        futureResultCallback.get()

        then:
        thrown(CodecConfigurationException)
    }

    def 'should follow the MongoIterable interface as expected'() {
        given:
        def cannedResults = [new Document('_id', 1), new Document('_id', 1), new Document('_id', 1)]
        def cursor = {
            Stub(AsyncBatchCursor) {
                def count = 0
                def results;
                def getResult = {
                    count++
                    results = count == 1 ? cannedResults : null
                    results
                }
                next(_) >> {
                    it[0].onResult(getResult(), null)
                }
                isClosed() >> { count >= 1 }
            }
        }
        def executor = new TestOperationExecutor([cursor(), cursor(), cursor(), cursor(), cursor()]);
        def mongoIterable = new DistinctIterableImpl<Document>(namespace, Document, codecRegistry, readPreference, executor, 'field')

        when:
        def results = new FutureResultCallback()
        mongoIterable.first(results)

        then:
        results.get() == cannedResults[0]

        when:
        def count = 0
        results = new FutureResultCallback()
        mongoIterable.forEach(new Block<Document>() {
            @Override
            void apply(Document document) {
                count++
            }
        }, results)
        results.get()

        then:
        count == 3

        when:
        def target = []
        results = new FutureResultCallback()
        mongoIterable.into(target, results)

        then:
        results.get() == cannedResults

        when:
        target = []
        results = new FutureResultCallback()
        mongoIterable.map(new Function<Document, Integer>() {
            @Override
            Integer apply(Document document) {
                document.getInteger('_id')
            }
        }).into(target, results)
        then:
        results.get() == [1, 1, 1]

        when:
        results = new FutureResultCallback()
        mongoIterable.batchCursor(results)
        def batchCursor = results.get()

        then:
        !batchCursor.isClosed()

        when:
        results = new FutureResultCallback()
        batchCursor.next(results)

        then:
        results.get() == cannedResults
        batchCursor.isClosed()
    }

}
