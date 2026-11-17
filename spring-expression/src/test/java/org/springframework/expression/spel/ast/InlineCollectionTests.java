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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import org.springframework.expression.spel.ExpressionState;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import static org.junit.Assert.*;

/**
 * Tests for {@link InlineList} and {@link InlineMap}.
 */
public class InlineCollectionTests {

	private static final SpelExpressionParser parser = new SpelExpressionParser();


	// InlineList

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void inlineListGetConstantValue() {
		InlineList inlineList = parseList("{1, 2, 3, 4}");
		assertTrue(inlineList.isConstant());
		List list1 = inlineList.getConstantValue();
		List list2 = (List) inlineList.getValue(expressionState());
		assertEquals(Arrays.asList(1, 2, 3, 4), list1);
		assertSame(list1, list2);
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void inlineListConstantListIsCached() {
		ExpressionState expressionState = expressionState();

		InlineList inlineList = parseList("{1, 2, 3, 4}");
		assertTrue(inlineList.isConstant());
		List list1 = (List) inlineList.getValue(expressionState);
		List list2 = (List) inlineList.getValue(expressionState);
		assertEquals(Arrays.asList(1, 2, 3, 4), list1);
		assertSame(list1, list2);
		// Constant inline lists are immutable.
		try {
			list1.add(999);
			fail("Should have thrown UnsupportedOperationException");
		}
		catch (UnsupportedOperationException ex) {
			// expected
		}
	}

	@Test
	public void inlineListDynamicListIsNotCached() {
		InlineList list = parseList("{1, (5 - 3), 3, 4}");
		assertFalse(list.isConstant());
		assertEquals(Arrays.asList(1, 2, 3, 4), list.getValue(expressionState()));
	}

	@Test
	public void inlineListNestedInlineListIsConstant() {
		InlineList list = parseList("{{1, 2}, {3, 4}}");
		assertTrue(list.isConstant());
	}

	@Test
	public void inlineListNestedDynamicInlineListIsNotConstant() {
		InlineList list = parseList("{{1, 2}, {(5 - 2), 4}}");
		assertFalse(list.isConstant());
	}


	// InlineMap

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void inlineMapGetConstantValue() {
		InlineMap inlineMap = parseMap("{1 : 2, 3 : 4}");
		assertTrue(inlineMap.isConstant());
		Map map1 = inlineMap.getConstantValue();
		Map map2 = (Map) inlineMap.getValue(expressionState());
		Map<Integer, Integer> expected = new HashMap<>();
		expected.put(1, 2);
		expected.put(3, 4);
		assertEquals(expected, map1);
		assertSame(map1, map2);
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void inlineMapConstantMapIsCached() {
		ExpressionState expressionState = expressionState();

		InlineMap inlineMap = parseMap("{1 : 2, 3 : 4}");
		assertTrue(inlineMap.isConstant());
		Map map1 = (Map) inlineMap.getValue(expressionState);
		Map map2 = (Map) inlineMap.getValue(expressionState);
		Map<Integer, Integer> expected = new HashMap<>();
		expected.put(1, 2);
		expected.put(3, 4);
		assertEquals(expected, map1);
		assertSame(map1, map2);
		// Constant inline maps are immutable.
		try {
			map1.put(99, "X");
			fail("Should have thrown UnsupportedOperationException");
		}
		catch (UnsupportedOperationException ex) {
			// expected
		}
	}

	@Test
	public void inlineMapDynamicMapIsNotCached() {
		InlineMap map = parseMap("{-1 : 2, (-2 - 1) : -4}");
		assertFalse(map.isConstant());
		Map<Integer, Integer> expected = new HashMap<>();
		expected.put(-1, 2);
		expected.put(-3, -4);
		assertEquals(expected, map.getValue(expressionState()));
	}

	@Test
	public void inlineMapWithPositiveKeysAndValuesIsCached() {
		InlineMap map = parseMap("{1 : 2, 3 : 4}");
		assertTrue(map.isConstant());
		Map<Integer, Integer> expected = new HashMap<>();
		expected.put(1, 2);
		expected.put(3, 4);
		assertEquals(expected, map.getValue(expressionState()));
	}

	@Test
	public void inlineMapWithStringKeysIsCached() {
		InlineMap map = parseMap("{name : 'foo', age : 12}");
		assertTrue(map.isConstant());
		Map<String, Object> expected = new HashMap<>();
		expected.put("name", "foo");
		expected.put("age", 12);
		assertEquals(expected, map.getValue(expressionState()));
	}


	private static InlineList parseList(String s) {
		SpelExpression expression = parseExpression(s);
		return (InlineList) expression.getAST();
	}

	private static InlineMap parseMap(String s) {
		SpelExpression expression = parseExpression(s);
		return (InlineMap) expression.getAST();
	}

	private static SpelExpression parseExpression(String s) {
		return (SpelExpression) parser.parseExpression(s);
	}

	private static ExpressionState expressionState() {
		return new ExpressionState(new StandardEvaluationContext());
	}

}
