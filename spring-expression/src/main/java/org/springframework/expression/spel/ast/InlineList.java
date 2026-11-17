/*
 * Copyright 2002-2014 the original author or authors.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.CodeFlow;
import org.springframework.expression.spel.ExpressionState;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.Assert;

/**
 * Represent a list in an expression, e.g. '{1,2,3}'
 *
 * @author Andy Clement
 * @since 3.0.4
 */
public class InlineList extends SpelNodeImpl {

	private final boolean isConstant;

	private volatile TypedValue constant;


	public InlineList(int pos, SpelNodeImpl... args) {
		super(pos, args);
		this.isConstant = determineIfConstant();
	}


	/**
	 * Determine whether this list is structurally eligible to be a constant
	 * value: whether all of its components are themselves constants or lists
	 * that contain only constants.
	 * <p>The actual constant value is created lazily on the first call to
	 * {@link #getValueInternal(ExpressionState)}.
	 */
	private boolean determineIfConstant() {
		for (int c = 0, max = getChildCount(); c < max; c++) {
			SpelNode child = getChild(c);
			if (child instanceof Literal) {
				continue;
			}
			if (child instanceof InlineList) {
				InlineList inlineList = (InlineList) child;
				if (inlineList.isConstant()) {
					continue;
				}
			}
			return false;
		}
		return true;
	}

	@Override
	public TypedValue getValueInternal(ExpressionState expressionState) throws EvaluationException {
		TypedValue result = this.constant;
		if (result != null) {
			return result;
		}
		result = createList(expressionState);
		if (this.isConstant) {
			this.constant = result;
		}
		return result;
	}

	private TypedValue createList(ExpressionState expressionState) throws EvaluationException {
		int childCount = getChildCount();
		expressionState.trackOperation();
		List<Object> list = new ArrayList<Object>(childCount);
		for (int c = 0; c < childCount; c++) {
			expressionState.trackOperation();
			list.add(getChild(c).getValue(expressionState));
		}
		return new TypedValue(this.isConstant ? Collections.unmodifiableList(list) : list);
	}

	@Override
	public String toStringAST() {
		StringBuilder sb = new StringBuilder("{");
		// String ast matches input string, not the 'toString()' of the resultant collection, which would use []
		int count = getChildCount();
		for (int c = 0; c < count; c++) {
			if (c > 0) {
				sb.append(",");
			}
			sb.append(getChild(c).toStringAST());
		}
		sb.append("}");
		return sb.toString();
	}

	/**
	 * Return whether this list is structurally a constant value.
	 * <p>Note that the resulting constant value is created lazily on the
	 * first call to {@link #getValueInternal(ExpressionState)} or
	 * {@link #getConstantValue()}.
	 */
	public boolean isConstant() {
		return this.isConstant;
	}

	/**
	 * Return the cached constant {@link List} value for this inline list,
	 * lazily creating it on first access.
	 * @see #isConstant()
	 */
	@SuppressWarnings("unchecked")
	public List<Object> getConstantValue() {
		Assert.state(this.isConstant, "Not a constant");
		TypedValue result = this.constant;
		if (result == null) {
			result = createList(new ExpressionState(new StandardEvaluationContext()));
			this.constant = result;
		}
		return (List<Object>) result.getValue();
	}

	@Override
	public boolean isCompilable() {
		return this.isConstant;
	}
	
	@Override
	public void generateCode(MethodVisitor mv, CodeFlow codeflow) {
		final String constantFieldName = "inlineList$"+codeflow.nextFieldId();
		final String clazzname = codeflow.getClassname();

		codeflow.registerNewField(new CodeFlow.FieldAdder() {
			public void generateField(ClassWriter cw, CodeFlow codeflow) {
				cw.visitField(ACC_PRIVATE|ACC_STATIC|ACC_FINAL, constantFieldName, "Ljava/util/List;", null, null);
			}
		});
		
		codeflow.registerNewClinit(new CodeFlow.ClinitAdder() {
			public void generateCode(MethodVisitor mv, CodeFlow codeflow) {
				generateClinitCode(clazzname,constantFieldName, mv,codeflow,false);
			}
		});
		
		mv.visitFieldInsn(GETSTATIC, clazzname, constantFieldName, "Ljava/util/List;");
		codeflow.pushDescriptor("Ljava/util/List");
	}
	
	void generateClinitCode(String clazzname, String constantFieldName, MethodVisitor mv, CodeFlow codeflow, boolean nested) {
		mv.visitTypeInsn(NEW, "java/util/ArrayList");
		mv.visitInsn(DUP);
		mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
		if (!nested) {
			mv.visitFieldInsn(PUTSTATIC, clazzname, constantFieldName, "Ljava/util/List;");
		}
		int childcount = getChildCount();		
		for (int c=0; c < childcount; c++) {
			if (!nested) {
				mv.visitFieldInsn(GETSTATIC, clazzname, constantFieldName, "Ljava/util/List;");
			}
			else {
				mv.visitInsn(DUP);
			}
			// The children might be further lists if they are not constants. In this
			// situation do not call back into generateCode() because it will register another clinit adder.
			// Instead, directly build the list here:
			if (children[c] instanceof InlineList) {
				((InlineList)children[c]).generateClinitCode(clazzname, constantFieldName, mv, codeflow, true);
			}
			else {
				children[c].generateCode(mv, codeflow);
				if (CodeFlow.isPrimitive(codeflow.lastDescriptor())) {
					CodeFlow.insertBoxIfNecessary(mv, codeflow.lastDescriptor().charAt(0));
				}
			}
			mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
			mv.visitInsn(POP);
		}
	}

}
