/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.expression.spel.ast;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.Test;

import org.springframework.expression.spel.ExpressionState;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import static org.junit.Assert.*;

/**
 * Unit tests for SpEL's {@code matches} operator, with an emphasis on the
 * bounded pattern cache.
 *
 * @see OperatorMatches
 */
public class OperatorMatchesTests {

	private static final int MAX_PATTERN_CACHE_SIZE = OperatorMatches.MAX_PATTERN_CACHE_SIZE;

	private final SpelExpressionParser parser = new SpelExpressionParser();


	@Test
	public void maxPatternCacheSize() {
		assertEquals(256, OperatorMatches.MAX_PATTERN_CACHE_SIZE);
	}

	@Test
	public void patternCacheDoesNotGrowBeyondTheMaximumCacheSize() throws Exception {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");

		// Feed the operator far more distinct regular expressions than the cache
		// is allowed to hold: prior to the fix, every one of them was retained in
		// an unbounded ConcurrentHashMap.
		int distinctRegexCount = MAX_PATTERN_CACHE_SIZE * 4;
		for (int i = 0; i < distinctRegexCount; i++) {
			context.setVariable("regex", distinctRegex(i));
			assertTrue(expression.getValue(context, Boolean.class));
		}

		Map<String, Pattern> patternCache = patternCache(expression);
		assertEquals(MAX_PATTERN_CACHE_SIZE, patternCache.size());

		// Evicted patterns are transparently recompiled, so results stay correct.
		context.setVariable("regex", distinctRegex(0));
		assertTrue(expression.getValue(context, Boolean.class));
		context.setVariable("regex", "does-not-match");
		assertFalse(expression.getValue(context, Boolean.class));
		assertTrue(patternCache.size() <= MAX_PATTERN_CACHE_SIZE);
	}

	@Test
	public void patternCacheIsSharedByAllMatchesNodesOfAnExpressionAndIsBounded() throws Exception {
		SpelExpression expression = (SpelExpression) this.parser.parseExpression(
				"#input matches #regex1 and #input matches #regex2");
		OperatorMatches first = (OperatorMatches) expression.getAST().getChild(0);
		OperatorMatches second = (OperatorMatches) expression.getAST().getChild(1);

		// Both matches nodes of the expression share the parser's single pattern cache.
		Map<String, Pattern> patternCache = patternCache(first);
		assertSame(patternCache, patternCache(second));

		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");
		int iterations = MAX_PATTERN_CACHE_SIZE * 2 + 1;
		for (int i = 0; i < iterations; i++) {
			context.setVariable("regex1", distinctRegex(i));
			context.setVariable("regex2", distinctRegex(iterations + i));
			assertTrue(expression.getValue(context, Boolean.class));
		}

		// 2 * 513 distinct regular expressions went through the shared cache,
		// yet the combined number of cached patterns stays bounded.
		assertSame(patternCache(first), patternCache(second));
		assertEquals(MAX_PATTERN_CACHE_SIZE, patternCache.size());
		Map<String, Pattern> snapshot = snapshot(patternCache);
		assertTrue(snapshot.containsKey(distinctRegex(iterations - 1)));
		assertTrue(snapshot.containsKey(distinctRegex(2 * iterations - 1)));
		assertFalse(snapshot.containsKey(distinctRegex(0)));
		assertFalse(snapshot.containsKey(distinctRegex(iterations)));
	}

	@Test
	public void patternCacheIsSharedBySelectionOverManyDistinctRegexes() throws Exception {
		SpelExpression expression = (SpelExpression) this.parser.parseExpression(
				"#regexes.?[#input matches #this].size() == #regexes.size() and !('xyz' matches #regexes[0])");
		Map<String, Pattern> patternCache = patternCache(findMatchesNode(expression.getAST(), 0));
		assertSame(patternCache, patternCache(findMatchesNode(expression.getAST(), 1)));

		List<String> regexes = new ArrayList<String>();
		for (int i = 0; i < MAX_PATTERN_CACHE_SIZE * 3; i++) {
			regexes.add(distinctRegex(i));
		}
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");
		context.setVariable("regexes", regexes);

		assertTrue(expression.getValue(context, Boolean.class));
		assertTrue(patternCache.size() <= MAX_PATTERN_CACHE_SIZE);
	}

	@Test
	public void patternCacheDoesNotGrowBeyondTheMaximumCacheSizeUnderConcurrentEvaluation() throws Exception {
		final SpelExpression expression = parseMatchesExpression();
		int threads = 8;
		final int distinctRegexCountPerThread = MAX_PATTERN_CACHE_SIZE * 2;

		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Boolean>> results = new ArrayList<Future<Boolean>>();
			for (int t = 0; t < threads; t++) {
				final int thread = t;
				results.add(executor.submit(new Callable<Boolean>() {
					@Override
					public Boolean call() {
						StandardEvaluationContext context = new StandardEvaluationContext();
						context.setVariable("input", "abc");
						boolean allMatched = true;
						for (int i = 0; i < distinctRegexCountPerThread; i++) {
							context.setVariable("regex", distinctRegex(thread * distinctRegexCountPerThread + i));
							allMatched &= Boolean.TRUE.equals(expression.getValue(context, Boolean.class));
							// Repeatedly hit a common regex as well, to exercise concurrent recency updates
							context.setVariable("regex", distinctRegex(-1));
							allMatched &= Boolean.TRUE.equals(expression.getValue(context, Boolean.class));
						}
						return allMatched;
					}
				}));
			}
			for (Future<Boolean> result : results) {
				assertTrue(result.get(1, TimeUnit.MINUTES));
			}
		}
		finally {
			executor.shutdownNow();
		}

		Map<String, Pattern> patternCache = patternCache(expression);
		assertTrue(patternCache.size() <= MAX_PATTERN_CACHE_SIZE);
		assertTrue(snapshot(patternCache).size() <= MAX_PATTERN_CACHE_SIZE);
	}

	@Test
	public void leastRecentlyUsedPatternIsEvictedFirst() throws Exception {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");

		// Fill the cache up to its maximum size.
		for (int i = 0; i < MAX_PATTERN_CACHE_SIZE; i++) {
			context.setVariable("regex", distinctRegex(i));
			assertTrue(expression.getValue(context, Boolean.class));
		}
		Map<String, Pattern> patternCache = patternCache(expression);
		assertEquals(MAX_PATTERN_CACHE_SIZE, patternCache.size());

		// Use the oldest entry again, then add a new one: the next oldest gets evicted.
		context.setVariable("regex", distinctRegex(0));
		assertTrue(expression.getValue(context, Boolean.class));
		context.setVariable("regex", distinctRegex(MAX_PATTERN_CACHE_SIZE));
		assertTrue(expression.getValue(context, Boolean.class));

		assertEquals(MAX_PATTERN_CACHE_SIZE, patternCache.size());
		Map<String, Pattern> snapshot = snapshot(patternCache);
		assertTrue(snapshot.containsKey(distinctRegex(0)));
		assertTrue(snapshot.containsKey(distinctRegex(MAX_PATTERN_CACHE_SIZE)));
		assertFalse(snapshot.containsKey(distinctRegex(1)));
	}

	@Test
	public void repeatedRegexIsCompiledOnlyOnce() throws Exception {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");
		context.setVariable("regex", "a.c");

		assertTrue(expression.getValue(context, Boolean.class));
		Map<String, Pattern> patternCache = patternCache(expression);
		Pattern pattern = snapshot(patternCache).get("a.c");
		assertNotNull(pattern);

		assertTrue(expression.getValue(context, Boolean.class));
		assertEquals(1, patternCache.size());
		assertSame(pattern, snapshot(patternCache).get("a.c"));
	}

	@Test
	public void invalidRegexIsRejectedAndNeverCached() throws Exception {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");
		context.setVariable("regex", "[abc");

		assertError(expression, context, SpelMessage.INVALID_PATTERN);
		assertError(expression, context, SpelMessage.INVALID_PATTERN);
		assertTrue(patternCache(expression).isEmpty());
	}

	@Test
	public void tooLongRegexIsRejectedAndNeverCached() throws Exception {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");
		context.setVariable("regex", repeat("a", 1001));

		assertError(expression, context, SpelMessage.MAX_REGEX_LENGTH_EXCEEDED);
		assertError(expression, context, SpelMessage.MAX_REGEX_LENGTH_EXCEEDED);
		assertTrue(patternCache(expression).isEmpty());
	}

	@Test
	public void cachedPatternDoesNotBypassRegexLengthCheck() throws Exception {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		String tooLongRegex = repeat("a", 1001);
		context.setVariable("input", tooLongRegex);
		context.setVariable("regex", tooLongRegex);

		// Even if a pattern for the regex is already present in the shared cache,
		// the regex length check must still be applied.
		patternCache(expression).putIfAbsent(tooLongRegex, Pattern.compile(tooLongRegex));
		SpelEvaluationException ex = assertError(expression, context, SpelMessage.MAX_REGEX_LENGTH_EXCEEDED);
		// Reported at the position of the matches operator
		assertEquals(7, ex.getPosition());
	}

	@Test
	public void matchesEvaluatesPositivelyAndNegatively() {
		StandardEvaluationContext context = new StandardEvaluationContext();
		assertTrue(this.parser.parseExpression("'5.00' matches '^-?\\d+(\\.\\d{2})?$'")
				.getValue(context, Boolean.class));
		assertFalse(this.parser.parseExpression("'5.0067' matches '^-?\\d+(\\.\\d{2})?$'")
				.getValue(context, Boolean.class));
	}

	@Test
	public void concurrentMapConstructorUsesSuppliedPatternCache() throws Exception {
		ConcurrentMap<String, Pattern> suppliedCache = new ConcurrentHashMap<String, Pattern>();
		OperatorMatches operator = new OperatorMatches(suppliedCache, -1,
				stringLiteral("'5.00'"), stringLiteral("'^-?\\d+(\\.\\d{2})?$'"));

		ExpressionState state = new ExpressionState(new StandardEvaluationContext());
		assertEquals(Boolean.TRUE, operator.getValueInternal(state).getValue());

		assertSame(suppliedCache, patternCache(operator));
		assertEquals(1, suppliedCache.size());
		assertTrue(suppliedCache.containsKey("^-?\\d+(\\.\\d{2})?$"));
	}

	@Test
	public void noCacheConstructorStillEvaluates() throws Exception {
		OperatorMatches operator = new OperatorMatches(-1,
				stringLiteral("'5.0067'"), stringLiteral("'^-?\\d+(\\.\\d{2})?$'"));

		ExpressionState state = new ExpressionState(new StandardEvaluationContext());
		assertEquals(Boolean.FALSE, operator.getValueInternal(state).getValue());
		assertEquals(1, patternCache(operator).size());
	}

	@Test
	public void noCacheConstructorUsesBoundedPatternCache() throws Exception {
		OperatorMatches operator = new OperatorMatches(-1,
				new VariableReference("input", -1), new VariableReference("regex", -1));
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");

		int distinctRegexCount = MAX_PATTERN_CACHE_SIZE * 4;
		for (int i = 0; i < distinctRegexCount; i++) {
			context.setVariable("regex", distinctRegex(i));
			assertEquals(Boolean.TRUE, operator.getValueInternal(new ExpressionState(context)).getValue());
		}
		context.setVariable("regex", "does-not-match");
		assertEquals(Boolean.FALSE, operator.getValueInternal(new ExpressionState(context)).getValue());

		Map<String, Pattern> patternCache = patternCache(operator);
		assertEquals(MAX_PATTERN_CACHE_SIZE, patternCache.size());
		Map<String, Pattern> snapshot = snapshot(patternCache);
		assertTrue(snapshot.containsKey(distinctRegex(0)));
		assertFalse(snapshot.containsKey("does-not-match"));
	}


	private SpelExpression parseMatchesExpression() {
		return (SpelExpression) this.parser.parseExpression("#input matches #regex");
	}

	private static SpelEvaluationException assertError(SpelExpression expression,
			StandardEvaluationContext context, SpelMessage message) {

		try {
			expression.getValue(context, Boolean.class);
			fail("Expected SpelEvaluationException with message " + message);
			return null;
		}
		catch (SpelEvaluationException ex) {
			assertEquals(message, ex.getMessageCode());
			return ex;
		}
	}

	private static String distinctRegex(int index) {
		// Always matches "abc", but the regex text itself is unique per index.
		return "abc|no-match-" + index;
	}

	private static String repeat(String str, int count) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < count; i++) {
			sb.append(str);
		}
		return sb.toString();
	}

	private static StringLiteral stringLiteral(String quotedValue) {
		return new StringLiteral(quotedValue, -1, quotedValue);
	}

	private static OperatorMatches findMatchesNode(SpelNode node, int index) {
		List<OperatorMatches> found = new ArrayList<OperatorMatches>();
		collectMatchesNodes(node, found);
		return found.get(index);
	}

	private static void collectMatchesNodes(SpelNode node, List<OperatorMatches> found) {
		if (node instanceof OperatorMatches) {
			found.add((OperatorMatches) node);
		}
		for (int i = 0; i < node.getChildCount(); i++) {
			collectMatchesNodes(node.getChild(i), found);
		}
	}

	private static ConcurrentMap<String, Pattern> patternCache(SpelExpression expression) throws Exception {
		return patternCache((OperatorMatches) expression.getAST());
	}

	@SuppressWarnings("unchecked")
	private static ConcurrentMap<String, Pattern> patternCache(OperatorMatches operator) throws Exception {
		Field field = OperatorMatches.class.getDeclaredField("patternCache");
		field.setAccessible(true);
		return (ConcurrentMap<String, Pattern>) field.get(operator);
	}

	/**
	 * Copy the cache contents without going through its lookup methods,
	 * so that assertions do not affect the recency order of the cache.
	 */
	private static Map<String, Pattern> snapshot(Map<String, Pattern> patternCache) {
		return new HashMap<String, Pattern>(patternCache);
	}

}
