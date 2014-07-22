package com.iastate.atlas.scripts;

import static com.ensoftcorp.atlas.java.core.script.Common.universe;
import static com.ensoftcorp.atlas.java.core.script.Common.edges;
import static com.ensoftcorp.atlas.java.core.script.CommonQueries.methodParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import atlas.c.scripts.Queries;

import com.ensoftcorp.atlas.core.db.graph.Graph;
import com.ensoftcorp.atlas.core.db.graph.GraphElement;
import com.ensoftcorp.atlas.core.db.set.AtlasHashSet;
import com.ensoftcorp.atlas.core.db.set.AtlasSet;
import com.ensoftcorp.atlas.core.index.common.SourceCorrespondence;
import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.atlas.java.core.highlight.H;
import com.ensoftcorp.atlas.java.core.highlight.Highlighter;
import com.ensoftcorp.atlas.java.core.highlight.Highlighter.ConflictStrategy;
import com.ensoftcorp.atlas.java.core.query.Q;
import com.ensoftcorp.atlas.java.core.script.Common;
import com.ensoftcorp.atlas.ui.viewer.graph.DisplayUtil;
import com.iastate.verifier.internal.Utils;

public class AnalysisScripts {
	
	public static String EVENT_FLOW_EDGE = "eventFlow";
	
	public static String EVENT_FLOW_NODE = "eventFlow";
	
	public static AtlasSet<GraphElement> atomicFunctions;
	
	private static Queries query = new Queries();
	
	static{
		atomicFunctions = new AtlasHashSet<GraphElement>();
		atomicFunctions.addAll(query.function("getbuf").eval().nodes());
		atomicFunctions.addAll(query.function("freebuf").eval().nodes());
		
		atomicFunctions.addAll(query.function("__mutex_init").eval().nodes());
		atomicFunctions.addAll(query.function("mutex_lock").eval().nodes());
		atomicFunctions.addAll(query.function("mutex_trylock").eval().nodes());
		atomicFunctions.addAll(query.function("mutex_lock_killable").eval().nodes());
		atomicFunctions.addAll(query.function("mutex_lock_interruptible").eval().nodes());
		atomicFunctions.addAll(query.function("mutex_unlock").eval().nodes());
	}
	
	/**
	 * Remove "eventFlow" tag from all nodes and edges
	 */
	public void deleteEFGs(){
		AtlasSet<GraphElement> edges = universe().edgesTaggedWithAll(EVENT_FLOW_EDGE).eval().edges();
		HashSet<GraphElement> toDelete = new HashSet<GraphElement>(); 
		for(GraphElement edge : edges){
			toDelete.add(edge);
		}
		
		for(GraphElement edge : toDelete){
			Graph.U.delete(edge);
		}
		
		AtlasSet<GraphElement> nodes = universe().nodesTaggedWithAll(EVENT_FLOW_NODE).eval().nodes();
		
		HashSet<GraphElement> ns = new HashSet<GraphElement>();
		for(GraphElement node : nodes){
			ns.add(node);
		}
		
		toDelete = new HashSet<GraphElement>(); 
		for(GraphElement node : ns){
			node.tags().remove(EVENT_FLOW_NODE);
			String name = (String) node.attr().get(XCSG.name);
			if(name.equals("EFG Entry") || name.equals("EFG Exit")){
				toDelete.add(node);
			}
		}
		
		for(GraphElement node : toDelete){
			Graph.U.delete(node);
		}
	}
	
	/**
	 * Returns the Event Flow Graph of a given function name
	 * @param name: The name of function
	 * @return The EFG of a function
	 */
	public Q EFG(String name){
		Q method = query.function(name);
		Q efg = universe().edgesTaggedWithAny(XCSG.Contains, EVENT_FLOW_EDGE).forward(method).nodesTaggedWithAny(EVENT_FLOW_NODE).induce(universe().edgesTaggedWithAll(EVENT_FLOW_EDGE));
		return efg;
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
			deleteEFGs();
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
				Q efg = EFG(functionName);
				
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
	
	public Q test2(int number){
		
		HashSet<String> locks = new HashSet<String>();
		locks.add("mutex_lock_interruptible");
		locks.add("mutex_lock");
		locks.add("mutex_trylock");
		locks.add("mutex_lock_killable");
		
		HashSet<String> unlocks = new HashSet<String>();
		unlocks.add("mutex_unlock");
		
		AtlasSet<GraphElement> e1 = new AtlasHashSet<GraphElement>();
		for(String l : locks){
			e1.addAll(query.function(l).eval().nodes());
		}
		Q e1Functions = Common.toQ(Common.toGraph(e1));
		
		AtlasSet<GraphElement> e2 = new AtlasHashSet<GraphElement>();
		for(String u : unlocks){
			e2.addAll(query.function(u).eval().nodes());
		}
		Q e2Functions = Common.toQ(Common.toGraph(e2));
				
		Q params = methodParameter(e1Functions.union(e2Functions), 0);
		
		Q dfReverseParams = universe().edgesTaggedWithAny(XCSG.DataFlow_Edge, com.ensoftcorp.atlas.c.core.query.Attr.Edge.ADDRESS_OF, com.ensoftcorp.atlas.c.core.query.Attr.Edge.POINTER_DEREFERENCE).reverseStep(params);
		Q rev = universe().edgesTaggedWithAny(XCSG.DataFlow_Edge, com.ensoftcorp.atlas.c.core.query.Attr.Edge.ADDRESS_OF, com.ensoftcorp.atlas.c.core.query.Attr.Edge.POINTER_DEREFERENCE).reverse(params);
		
		AtlasSet<GraphElement> paramsPassed = dfReverseParams.roots().eval().nodes();
		
		int count = -1;
		for(GraphElement parameter : paramsPassed){
			if(++count == number){
				deleteEFGs();
				Q param = Common.toQ(Common.toGraph(parameter));
				Q dfReverse = universe().edgesTaggedWithAny(XCSG.DataFlow_Edge, com.ensoftcorp.atlas.c.core.query.Attr.Edge.ADDRESS_OF, com.ensoftcorp.atlas.c.core.query.Attr.Edge.POINTER_DEREFERENCE).reverse(param);
				Q currentSignature = dfReverse.roots().nodesTaggedWithAny(XCSG.Variable);
				Q between = rev.between(currentSignature, params);
				//between = Common.extend(between, XCSG.Contains);
				//DisplayUtil.displayGraph(between.eval());
				
				Q dfParams = between.intersection(dfReverseParams).roots();
				Q controlFlowNodes = universe().edgesTaggedWithAll(XCSG.Contains).reverseStep(dfParams).nodesTaggedWithAll(XCSG.ControlFlow_Node);
				Q callSites = universe().edgesTaggedWithAll(XCSG.Contains).forward(controlFlowNodes).nodesTaggedWithAll(XCSG.CallSite);
				//DisplayUtil.displayGraph(callSites.eval());
				
				Q mpg = getMPG(callSites, locks, unlocks);
				
				mpg = mpg.union(e1Functions, e2Functions);
				mpg = mpg.induce(universe().edgesTaggedWithAll(XCSG.Call));
				
				verify(mpg, controlFlowNodes, e1, e2);
				
			}
		}
		return null;
	}
	
	public void verify(Q envelope, Q controlFlowNodes, AtlasSet<GraphElement> e1, AtlasSet<GraphElement> e2){
		HashMap<GraphElement, Graph> functionFlowMap = new HashMap<GraphElement, Graph>();
		
		Graph envelopeGraph = envelope.eval();
		DisplayUtil.displayGraph(envelopeGraph, new Highlighter(), "Envelope");
		
		for(GraphElement function : envelopeGraph.nodes()){
			if(e1.contains(function) || e2.contains(function))
				continue;
			
			String functionName = function.attr().get(XCSG.name).toString();
			Q cfg = query.CFG(functionName);
			Q intersection = cfg.intersection(controlFlowNodes);
			H h = new Highlighter(ConflictStrategy.COLOR);
			h.highlight(intersection, java.awt.Color.RED);
			Graph g = cfg.eval();
			DisplayUtil.displayGraph(g, h, "CFG-" + functionName);
			
			Utils.addEFGToIndex(function, g, Utils.createEventFlowGraph(g, intersection.eval().nodes()));
			Q efg = EFG(functionName);
			
			DisplayUtil.displayGraph(efg.eval(), h, "EFG-" + functionName);
			functionFlowMap.put(function, efg.eval());
		}
		//Verifier verifier = new Verifier(callSite.address().toAddressString(), envelope.eval(), functionFlowMap);
		//verifier.run();
	}
	
	public Q getMPG(Q controlFlowNodes, HashSet<String> locks, HashSet<String> unlocks){
		AtlasSet<GraphElement> nodes = controlFlowNodes.eval().nodes();
		HashMap<GraphElement, HashMap<String, AtlasSet<GraphElement>>> functionMap = new HashMap<GraphElement, HashMap<String,AtlasSet<GraphElement>>>(); 
		
		for(GraphElement node : nodes){
			Q functionQ = query.getFunctionContainingElement(node);
			GraphElement functionNode = functionQ.eval().nodes().getFirst();
			
			boolean callingLock = false;
			boolean callingUnlock = false;
			if(isCalling(node, locks)){
				callingLock = true;
			}
			
			if(isCalling(node, unlocks)){
				callingUnlock = true;
			}
			
			if(callingLock || callingUnlock){
				HashMap<String, AtlasSet<GraphElement>> luMap = new HashMap<String, AtlasSet<GraphElement>>();
				
				if(functionMap.containsKey(functionNode)){
					luMap = functionMap.get(functionNode);
				}
				
				if(callingLock){
					AtlasSet<GraphElement> callL = new AtlasHashSet<GraphElement>();
					if(luMap.containsKey("L")){
						callL = luMap.get("L");
					}
					callL.add(node);
					luMap.put("L", callL);
				}
				
				if(callingUnlock){
					AtlasSet<GraphElement> callU = new AtlasHashSet<GraphElement>();
					if(luMap.containsKey("U")){
						callU = luMap.get("U");
					}
					callU.add(node);
					luMap.put("U", callU);
				}
				functionMap.put(functionNode, luMap);
			}
		}
			
		AtlasSet<GraphElement> callL = new AtlasHashSet<GraphElement>();
		AtlasSet<GraphElement> callU = new AtlasHashSet<GraphElement>();
		AtlasSet<GraphElement> unbalanced = new AtlasHashSet<GraphElement>();
		
		for(GraphElement f : functionMap.keySet()){
			HashMap<String, AtlasSet<GraphElement>> nodesMap = functionMap.get(f);
			if(nodesMap.size() == 1 && nodesMap.keySet().contains("L")){
				callL.add(f);
				continue;
			}
			
			if(nodesMap.size() == 1 && nodesMap.keySet().contains("U")){
				callU.add(f);
				continue;
			}
			
			callL.add(f);
			callU.add(f);
			
			AtlasSet<GraphElement> lNodes = nodesMap.get("L");
			List<Integer> ls = new ArrayList<Integer>();
			for(GraphElement l : lNodes){
				SourceCorrespondence sc = (SourceCorrespondence) l.attr().get(XCSG.sourceCorrespondence);
				ls.add(sc.offset);
			}
			AtlasSet<GraphElement> uNodes = nodesMap.get("U");
			List<Integer> us = new ArrayList<Integer>();
			for(GraphElement u : uNodes){
				SourceCorrespondence sc = (SourceCorrespondence) u.attr().get(XCSG.sourceCorrespondence);
				us.add(sc.offset);
			}
			
			Collections.sort(ls);
			Collections.sort(us);
			
			if(us.get(us.size() - 1) <= ls.get(ls.size() - 1)){
				unbalanced.add(f);
				callU.remove(f);
			}
		}
		
		Q callLQ = Common.toQ(Common.toGraph(callL));
		Q callUQ = Common.toQ(Common.toGraph(callU));
		//Q callLU = callLQ.intersection(callUQ);
		Q rcg_lock = universe().edgesTaggedWithAll(XCSG.Call).reverse(callLQ);
		Q rcg_unlock = universe().edgesTaggedWithAll(XCSG.Call).reverse(callUQ);
		Q rcg_both = rcg_lock.intersection(rcg_unlock);
		Q rcg_c = rcg_lock.union(rcg_unlock);
		Q rcg_lock_only = rcg_lock.difference(rcg_both);
		Q rcg_unlock_only = rcg_unlock.difference(rcg_both);
		Q call_lock_only = callLQ.union(universe().edgesTaggedWithAll(XCSG.Call).reverseStep(rcg_lock_only));
		Q call_unlock_only = callUQ.union(universe().edgesTaggedWithAll(XCSG.Call).reverseStep(rcg_unlock_only));
		Q call_c_only = call_lock_only.union(call_unlock_only);
		Q balanced = call_c_only.intersection(rcg_both);
		Q ubc = balanced.union(rcg_lock_only, rcg_unlock_only);
		Q mpg = rcg_c.intersection(universe().edgesTaggedWithAll(XCSG.Call).forward(ubc));
		
		return mpg;
	}
	
	public boolean isCalling(GraphElement node, HashSet<String> functions){
		for(String f : functions){
			if(((String) node.attr().get(XCSG.name)).contains(f + "("))
				return true;
		}
		return false;
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
		deleteEFGs();
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
			Q efg = EFG(functionName);
			
			DisplayUtil.displayGraph(efg.eval(), h, "EFG-" + functionName);
			
			//GraphUtils.write(Utils.transformAtlasGraph(g, intersection.eval().nodes()),"/home/ahmed/Desktop/cfg.dot");
			//GraphUtils.write(Utils.createEventFlowGraph(g, intersection.eval().nodes()),"/home/ahmed/Desktop/efg.dot");
		}
	}
}
