/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.java.decompiler.main.rels;

import org.jetbrains.java.decompiler.code.InstructionSequence;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.modules.code.DeadCodeHelper;
import org.jetbrains.java.decompiler.modules.decompiler.*;
import org.jetbrains.java.decompiler.modules.decompiler.deobfuscator.ExceptionDeobfuscator;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarProcessor;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructMethod;

import java.io.IOException;

public class MethodProcessorThread implements Runnable {

  public final Object lock = new Object();

  private final StructMethod method;
  private final VarProcessor varproc;
  private final DecompilerContext parentContext;

  private volatile RootStatement root;
  private volatile Throwable error;

  public MethodProcessorThread(StructMethod method, VarProcessor varproc, DecompilerContext parentContext) {
    this.method = method;
    this.varproc = varproc;
    this.parentContext = parentContext;
  }

  public void run() {

    DecompilerContext.setCurrentContext(parentContext);

    error = null;
    root = null;

    try {
      root = codeToJava(method, varproc);

      synchronized (lock) {
        lock.notifyAll();
      }
    }
    catch (ThreadDeath ex) {
      throw ex;
    }
    catch (Throwable ex) {
      error = ex;
    }
  }

  public static RootStatement codeToJava(StructMethod mt, VarProcessor varproc) throws IOException {

    StructClass cl = mt.getClassStruct();

    boolean isInitializer = "<clinit>".equals(mt.getName()); // for now static initializer only

    mt.expandData();
    InstructionSequence seq = mt.getInstructionSequence();
    ControlFlowGraph graph = new ControlFlowGraph(seq);

    //		System.out.println(graph.toString());


    //		if(mt.getName().endsWith("_getActiveServers")) {
    //			System.out.println();
    //		}

    //DotExporter.toDotFile(graph, new File("c:\\Temp\\fern1.dot"), true);

    DeadCodeHelper.removeDeadBlocks(graph);
    graph.inlineJsr(mt);

    //		DotExporter.toDotFile(graph, new File("c:\\Temp\\fern4.dot"), true);

    // TODO: move to the start, before jsr inlining
    DeadCodeHelper.connectDummyExitBlock(graph);

    DeadCodeHelper.removeGotos(graph);
    ExceptionDeobfuscator.removeCircularRanges(graph);
    //DeadCodeHelper.removeCircularRanges(graph);


    //		DotExporter.toDotFile(graph, new File("c:\\Temp\\fern3.dot"), true);

    ExceptionDeobfuscator.restorePopRanges(graph);

    if (DecompilerContext.getOption(IFernflowerPreferences.REMOVE_EMPTY_RANGES)) {
      ExceptionDeobfuscator.removeEmptyRanges(graph);
    }

    //		DotExporter.toDotFile(graph, new File("c:\\Temp\\fern3.dot"), true);

    if (DecompilerContext.getOption(IFernflowerPreferences.NO_EXCEPTIONS_RETURN)) {
      // special case: single return instruction outside of a protected range
      DeadCodeHelper.incorporateValueReturns(graph);
    }

    //		DotExporter.toDotFile(graph, new File("c:\\Temp\\fern5.dot"), true);

    //		ExceptionDeobfuscator.restorePopRanges(graph);
    ExceptionDeobfuscator.insertEmptyExceptionHandlerBlocks(graph);

    DeadCodeHelper.mergeBasicBlocks(graph);

    DecompilerContext.getCounterContainer().setCounter(CounterContainer.VAR_COUNTER, mt.getLocalVariables());

    //DotExporter.toDotFile(graph, new File("c:\\Temp\\fern3.dot"), true);
    //System.out.println(graph.toString());

    if (ExceptionDeobfuscator.hasObfuscatedExceptions(graph)) {
      DecompilerContext.getLogger().writeMessage("Heavily obfuscated exception ranges found!", IFernflowerLogger.WARNING);
    }

    RootStatement root = DomHelper.parseGraph(graph);

    FinallyProcessor fproc = new FinallyProcessor(varproc);
    while (fproc.iterateGraph(mt, root, graph)) {

      //DotExporter.toDotFile(graph, new File("c:\\Temp\\fern2.dot"), true);
      //System.out.println(graph.toString());
      //System.out.println("~~~~~~~~~~~~~~~~~~~~~~ \r\n"+root.toJava());

      root = DomHelper.parseGraph(graph);
    }

    // remove synchronized exception handler
    // not until now because of comparison between synchronized statements in the finally cycle
    DomHelper.removeSynchronizedHandler(root);

    //		DotExporter.toDotFile(graph, new File("c:\\Temp\\fern3.dot"), true);
    //		System.out.println(graph.toString());

    //		LabelHelper.lowContinueLabels(root, new HashSet<StatEdge>());

    SequenceHelper.condenseSequences(root);

    ClearStructHelper.clearStatements(root);

    ExprProcessor proc = new ExprProcessor();
    proc.processStatement(root, cl);

    //		DotExporter.toDotFile(graph, new File("c:\\Temp\\fern3.dot"), true);
    //		System.out.println(graph.toString());

    //System.out.println("~~~~~~~~~~~~~~~~~~~~~~ \r\n"+root.toJava());

    while (true) {
      StackVarsProcessor stackproc = new StackVarsProcessor();
      stackproc.simplifyStackVars(root, mt, cl);

      //System.out.println("~~~~~~~~~~~~~~~~~~~~~~ \r\n"+root.toJava());

      varproc.setVarVersions(root);

      //			System.out.println("~~~~~~~~~~~~~~~~~~~~~~ \r\n"+root.toJava());

      if (!new PPandMMHelper().findPPandMM(root)) {
        break;
      }
    }

    while (true) {

      LabelHelper.cleanUpEdges(root);

      while (true) {

        MergeHelper.enhanceLoops(root);

        if (LoopExtractHelper.extractLoops(root)) {
          continue;
        }

        if (!IfHelper.mergeAllIfs(root)) {
          break;
        }
      }

      if (DecompilerContext.getOption(IFernflowerPreferences.IDEA_NOT_NULL_ANNOTATION)) {

        if (IdeaNotNullHelper.removeHardcodedChecks(root, mt)) {

          SequenceHelper.condenseSequences(root);

          StackVarsProcessor stackproc = new StackVarsProcessor();
          stackproc.simplifyStackVars(root, mt, cl);

          varproc.setVarVersions(root);
        }
      }

      LabelHelper.identifyLabels(root);

      //			System.out.println("~~~~~~~~~~~~~~~~~~~~~~ \r\n"+root.toJava());

      if (InlineSingleBlockHelper.inlineSingleBlocks(root)) {
        continue;
      }

      // initializer may have at most one return point, so no transformation of method exits permitted
      if (isInitializer || !ExitHelper.condenseExits(root)) {
        break;
      }

      // FIXME: !!
      //			if(!EliminateLoopsHelper.eliminateLoops(root)) {
      //				break;
      //			}
    }

    ExitHelper.removeRedundantReturns(root);

    SecondaryFunctionsHelper.identifySecondaryFunctions(root);

    varproc.setVarDefinitions(root);

    // must be the last invocation, because it makes the statement structure inconsistent
    // FIXME: new edge type needed
    LabelHelper.replaceContinueWithBreak(root);

    mt.releaseResources();

    //		System.out.println("++++++++++++++++++++++/// \r\n"+root.toJava());

    return root;
  }

  public RootStatement getResult() throws Throwable {
    Throwable t = error;
    if (t != null) throw t;
    return root;
  }

  public Throwable getError() {
    return error;
  }
}
