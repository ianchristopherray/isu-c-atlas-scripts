package com.iastate.atlas.scripts;

import static com.ensoftcorp.atlas.java.core.script.Common.universe;
import static com.ensoftcorp.atlas.java.core.script.Common.edges;
import static com.ensoftcorp.atlas.java.core.script.CommonQueries.methodParameter;

import java.util.HashMap;

import atlas.c.scripts.Queries;

import com.ensoftcorp.atlas.core.db.graph.Graph;
import com.ensoftcorp.atlas.core.db.graph.GraphElement;
import com.ensoftcorp.atlas.core.db.set.AtlasHashSet;
import com.ensoftcorp.atlas.core.db.set.AtlasSet;
import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.atlas.java.core.highlight.H;
import com.ensoftcorp.atlas.java.core.highlight.Highlighter;
import com.ensoftcorp.atlas.java.core.highlight.Highlighter.ConflictStrategy;
import com.ensoftcorp.atlas.java.core.query.Q;
import com.ensoftcorp.atlas.java.core.script.Common;
import com.ensoftcorp.atlas.ui.viewer.graph.DisplayUtil;
import com.iastate.verifier.internal.Utils;

public class AnalysisScripts {
		
	public static AtlasSet<GraphElement> atomicFunctions;
	
	private static Queries query = new Queries();
	
	static{
		atomicFunctions = new AtlasHashSet<GraphElement>();
		atomicFunctions.addAll(query.function("getbuf").eval().nodes());
		atomicFunctions.addAll(query.function("freebuf").eval().nodes());
		
		atomicFunctions.addAll(query.function("kmalloc").eval().nodes());
		atomicFunctions.addAll(query.function("kfree").eval().nodes());
		
		atomicFunctions.addAll(query.function("__mutex_init").eval().nodes());
		atomicFunctions.addAll(query.function("mutex_lock").eval().nodes());
		atomicFunctions.addAll(query.function("mutex_trylock").eval().nodes());
		atomicFunctions.addAll(query.function("mutex_lock_killable").eval().nodes());
		atomicFunctions.addAll(query.function("mutex_lock_interruptible").eval().nodes());
		atomicFunctions.addAll(query.function("mutex_unlock").eval().nodes());
	}
	
	/**
	 * For a given functions, return the call sites
	 * @param e: The set of functions
	 * @return
	 */
	public AtlasSet<GraphElement> getEventCallSitesFromReturn(AtlasSet<GraphElement> e){
		Q returnsFromE = query.functionReturn(Common.toQ(Common.toGraph(e)));
		Q callSites = edges(XCSG.DataFlow_Edge).forwardStep(returnsFromE);
		return callSites.eval().leaves(); 
	}
	
	public AtlasSet<GraphElement> getEventCallSitesFromParameter(AtlasSet<GraphElement> e, int parameterIndex){
		Q paramFromE = methodParameter(Common.toQ(Common.toGraph(e)), parameterIndex);
		Q callSites = edges(XCSG.DataFlow_Edge).reverseStep(paramFromE);
		callSites = universe().edgesTaggedWithAll(XCSG.DataFlow_Edge).reverseStep(callSites);
		return callSites.eval().roots(); 
	}
	
	/**
	 * Computes the forward data flow from a given event/node
	 * @param event: The event node where the analysis should start
	 * @return
	 */
	public Q getForwardDataFlowFromEvent(Q event){
		Q dfForward = edges(XCSG.DataFlow_Edge).forwardStep(event);
		Graph dfForwardPrime = dfForward.eval();
		long nodesCount = 0;
		long edgesCount = 0;
		do{
			nodesCount = dfForwardPrime.nodes().size();
			edgesCount = dfForwardPrime.edges().size();
			AtlasSet<GraphElement> leaves = dfForwardPrime.leaves();
			for(GraphElement element : leaves){
				Graph containingMethod = query.getFunctionContainingElement(element).eval();
				if(containingMethod.nodes().isEmpty() || (!containingMethod.nodes().isEmpty() && !atomicFunctions.contains(containingMethod.nodes().getFirst()))){
					dfForward = dfForward.union(edges(XCSG.DataFlow_Edge).forwardStep(Common.toQ(Common.toGraph(element))));	
				}
			}
			dfForwardPrime = dfForward.eval();
		}while(nodesCount != dfForwardPrime.nodes().size() && edgesCount != dfForwardPrime.edges().size());
		return dfForward;
	}
	
	public Q restrictOnControlNodes(Q dataFlow){
		dataFlow = Common.extend(dataFlow, XCSG.Contains);
		return dataFlow.nodesTaggedWithAll(XCSG.ControlFlow_Node);
	}
	
	public Q restrictOnFunctionCalls(Q dataFlow){
		dataFlow = Common.extend(dataFlow, XCSG.Contains);
		return dataFlow.nodesTaggedWithAll(XCSG.Function).induce(edges(XCSG.Call));		
	}
	
	public void verifyProperty(AtlasSet<GraphElement> e1, AtlasSet<GraphElement> e2, AtlasSet<GraphElement> eventCallSites){
		for(GraphElement callSite : eventCallSites){
			query.deleteEFGs();
			GraphElement containingMethod = query.getFunctionContainingElement(callSite).eval().nodes().getFirst();
			if(!containingMethod.attr().get(XCSG.name).equals("dswrite"))
				continue;
			Q callSiteEvent = Common.toQ(Common.toGraph(callSite));
			Q dataFlow = getForwardDataFlowFromEvent(callSiteEvent);
			Q d = Common.extend(dataFlow, XCSG.Contains);
			DisplayUtil.displayGraph(d.eval(), new Highlighter(), "Envelope");
			Q envelope = restrictOnFunctionCalls(dataFlow).difference(Common.toQ(Common.toGraph(e1)), Common.toQ(Common.toGraph(e2)));
			Q controlNodes = restrictOnControlNodes(dataFlow);
			
			AtlasSet<GraphElement> functions = envelope.nodesTaggedWithAll(XCSG.Function).eval().nodes();
			HashMap<GraphElement, Graph> functionFlowMap = new HashMap<GraphElement, Graph>();
			
			DisplayUtil.displayGraph(envelope.eval(), new Highlighter(), "Envelope");
			
			for(GraphElement function : functions){
				String functionName = function.attr().get(XCSG.name).toString();
				Q cfg = query.CFG(functionName);
				Q intersection = cfg.intersection(controlNodes);
				H h = new Highlighter(ConflictStrategy.COLOR);
				h.highlight(intersection, java.awt.Color.RED);
				//cfg = Common.extend(cfg, Edge.DECLARES);
				Graph g = cfg.eval();
				DisplayUtil.displayGraph(g, h, "CFG-" + functionName);
				
				//GraphUtils.write(Utils.transformAtlasGraph(g, intersection.eval().nodes()),"/home/ahmed/Desktop/cfg-"+functionName+".dot");
				
				Utils.addEFGToIndex(function, g, Utils.createEventFlowGraph(g, intersection.eval().nodes()));
				Q efg = query.EFG(functionName);
				
				//DisplayUtil.displayGraph(efg.eval(), h, "EFG-" + functionName);
				functionFlowMap.put(function, efg.eval());
			}
			//Verifier verifier = new Verifier(callSite.address().toAddressString(), envelope.eval(), functionFlowMap);
			//verifier.run();
			break;
		}
	}
	
	public void verifyMutexSafeSynchronization(){
		AtlasSet<GraphElement> e1 = query.function("mutex_lock").eval().nodes();
		AtlasSet<GraphElement> e2 = query.function("mutex_unlock").eval().nodes();
		AtlasSet<GraphElement> eventCallSites = getEventCallSitesFromParameter(e1, 0);
		verifyProperty(e1, e2, eventCallSites);
	}
	
	public Q test(int number){
		AtlasSet<GraphElement> e1 = new AtlasHashSet<GraphElement>();
		e1.addAll(query.function("mutex_lock").eval().nodes());
		e1.addAll(query.function("mutex_trylock").eval().nodes());
		e1.addAll(query.function("mutex_lock_killable").eval().nodes());
		e1.addAll(query.function("mutex_lock_interruptible").eval().nodes());
		
		Q parameter = methodParameter(Common.toQ(Common.toGraph(e1)), 0);
		
		Q roots = universe().edgesTaggedWithAny(XCSG.DataFlow_Edge, com.ensoftcorp.atlas.c.core.query.Attr.Edge.ADDRESS_OF, com.ensoftcorp.atlas.c.core.query.Attr.Edge.POINTER_DEREFERENCE).reverseStep(parameter).roots();
		AtlasSet<GraphElement> elements = roots.eval().nodes();
		int count = -1;
		for(GraphElement element : elements){
			//Q containingMethod = getContainingMethod(element);
			//GraphElement methodElement = containingMethod.nodesTaggedWithAll(XCSG.Function).eval().nodes().getFirst();
			//String methodName = (String) methodElement.attr().get(XCSG.name);
			if(++count == number){
			//if(methodName.equals("echo_set_canon_col")){
				Q currentQ = Common.toQ(Common.toGraph(element));
				Q dfReverse = universe().edgesTaggedWithAny(XCSG.DataFlow_Edge, com.ensoftcorp.atlas.c.core.query.Attr.Edge.ADDRESS_OF, com.ensoftcorp.atlas.c.core.query.Attr.Edge.POINTER_DEREFERENCE).reverse(currentQ);
				Q dfForward = universe().edgesTaggedWithAny(XCSG.DataFlow_Edge, com.ensoftcorp.atlas.c.core.query.Attr.Edge.ADDRESS_OF, com.ensoftcorp.atlas.c.core.query.Attr.Edge.POINTER_DEREFERENCE).forwardStep(dfReverse);
				
				Graph dfForwardPrime = dfForward.eval();
				long nodesCount = 0;
				long edgesCount = 0;
				do{
					nodesCount = dfForwardPrime.nodes().size();
					edgesCount = dfForwardPrime.edges().size();
					AtlasSet<GraphElement> leaves = dfForwardPrime.leaves();
					for(GraphElement e : leaves){
						Graph cm = query.getFunctionContainingElement(e).eval();
						if(cm.nodes().isEmpty() || (!cm.nodes().isEmpty() && !atomicFunctions.contains(cm.nodes().getFirst()))){
							dfForward = dfForward.union(edges(XCSG.DataFlow_Edge).forwardStep(Common.toQ(Common.toGraph(e))));	
						}
					}
					dfForwardPrime = dfForward.eval();
				}while(nodesCount != dfForwardPrime.nodes().size() && edgesCount != dfForwardPrime.edges().size());
				return dfForward;
			//}
			}
		}
		return null;
	}
	
	public void verifyXinu(){
		AtlasSet<GraphElement> e1 = query.function("getbuf").eval().nodes();
		AtlasSet<GraphElement> e2 = query.function("freebuf").eval().nodes();
		AtlasSet<GraphElement> eventCallSites = getEventCallSitesFromReturn(e1);
		verifyProperty(e1, e2, eventCallSites);
	}
	
	public void analyze(){
		Q get = query.functionReturn(query.function("getbuf"));
		Q dfGet = edges(XCSG.DataFlow_Edge).forwardStep(get);
		Graph g = dfGet.eval();
		for(GraphElement e : g.leaves()){
			Q method = query.getFunctionContainingElement(e);
			String currentMethod = method.eval().nodes().iterator().next().attr().get(XCSG.name).toString();
			Q q = analyze(currentMethod);
			q = Common.extend(q, XCSG.Contains);
			Graph graph = q.eval();
			Highlighter h = new Highlighter();
			h.highlight(dfGet, java.awt.Color.RED);
			DisplayUtil.displayGraph(graph, h, currentMethod);
		}
	}
	
	/**
	 *  The function produces the EFGs and call graphs needs for the verification based on the highlighted event nodes
	 * @param function: The starting point for the analysis
	 * @return
	 */
	public Q analyze(String function){
		Q get = query.functionReturn(query.function("getbuf"));
		Q dfGet = edges(XCSG.DataFlow_Edge).forwardStep(get);
		Graph g = dfGet.eval();
		Q eprime = null;
		for(GraphElement e : g.leaves()){
			Q method = query.getFunctionContainingElement(e);
			String currentMethod = method.eval().nodes().iterator().next().attr().get(XCSG.name).toString();
			if(currentMethod.equals(function)){
				eprime = Common.toQ(Common.toGraph(e));
				break;
			}
		}
		Q dfGetForward = edges(XCSG.DataFlow_Edge).forwardStep(eprime);
		Q dataFlow = dfGetForward;
		long nodesCount = 0;
		long edgesCount = 0;
		do{
			Graph gprime = dataFlow.eval();
			nodesCount = gprime.nodes().size();
			edgesCount = gprime.edges().size();
			AtlasSet<GraphElement> leaves = gprime.leaves();
			for(GraphElement element : leaves){
				Q method = query.getFunctionContainingElement(element);
				if(!method.eval().nodes().isEmpty()){
					String currentMethod = method.eval().nodes().iterator().next().attr().get(XCSG.name).toString();
					if(!currentMethod.equals("freebuf")){
						dataFlow = dataFlow.union(edges(XCSG.DataFlow_Edge).forwardStep(Common.toQ(Common.toGraph(element))));
					}	
				}else{
					dataFlow = dataFlow.union(edges(XCSG.DataFlow_Edge).forwardStep(Common.toQ(Common.toGraph(element))));
				}
			}
		}while(nodesCount != dataFlow.eval().nodes().size() && edgesCount != dataFlow.eval().edges().size());
		return dataFlow;
	}
	
	public void highlightEventNodes(String function){
		query.deleteEFGs();
		Q dataFlow = analyze(function);
		Q functionCalls = restrictOnFunctionCalls(dataFlow);
		Q controlNodes = restrictOnControlNodes(dataFlow);
		
		AtlasSet<GraphElement> functions = functionCalls.nodesTaggedWithAll(XCSG.Function).eval().nodes();
		for(GraphElement functionCall : functions){
			String functionName = functionCall.attr().get(XCSG.name).toString();
			Q cfg = query.CFG(functionName);
			Q intersection = cfg.intersection(controlNodes);
			H h = new Highlighter(ConflictStrategy.COLOR);
			h.highlight(intersection, java.awt.Color.RED);
			//cfg = Common.extend(cfg, Edge.DECLARES);
			Graph g = cfg.eval();
			DisplayUtil.displayGraph(g, h, "CFG-" + functionName);
			
			Utils.addEFGToIndex(functionCall, g, Utils.createEventFlowGraph(g, intersection.eval().nodes()));
			Q efg = query.EFG(functionName);
			
			DisplayUtil.displayGraph(efg.eval(), h, "EFG-" + functionName);
			
			//GraphUtils.write(Utils.transformAtlasGraph(g, intersection.eval().nodes()),"/home/ahmed/Desktop/cfg.dot");
			//GraphUtils.write(Utils.createEventFlowGraph(g, intersection.eval().nodes()),"/home/ahmed/Desktop/efg.dot");
		}
	}
}
