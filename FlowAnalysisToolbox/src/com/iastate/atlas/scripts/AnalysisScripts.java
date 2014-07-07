package com.iastate.atlas.scripts;

import static com.ensoftcorp.atlas.java.core.script.Common.universe;
import static com.ensoftcorp.atlas.java.core.script.Common.edges;

import java.util.HashMap;
import java.util.HashSet;

import com.alexmerz.graphviz.GraphUtils;
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
	
	public static String EVENT_FLOW_EDGE = "eventFlow";
	
	public static String EVENT_FLOW_NODE = "eventFlow";
	
	public static AtlasSet<GraphElement> atomicFunctions;
	
	static{
		atomicFunctions = new AtlasHashSet<GraphElement>();
		atomicFunctions.addAll(function("getbuf").eval().nodes());
		atomicFunctions.addAll(function("freebuf").eval().nodes());
	}
	
	/**
	 * Returns the set of nodes that correspond to a definition of the function given by "name"
	 * @param name: The name of function
	 * @return The set of nodes representing a definition for that function
	 */
	public static Q function(String name) { 
		return universe().nodesTaggedWithAll(XCSG.Function, "isDef").selectNode(XCSG.name, name); 
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
	}
	
	/**
	 * Returns the CFG of a given function name
	 * @param name: The name of function
	 * @return The CFG of a function
	 */
	public Q CFG(String name){
		Q method = function(name);
		Q cfg = edges(XCSG.Contains).forward(method).nodesTaggedWithAny(XCSG.ControlFlow_Node).induce(edges(XCSG.ControlFlow_Edge));
		return cfg;
	}
	
	/**
	 * Returns the Event Flow Graph of a given function name
	 * @param name: The name of function
	 * @return The EFG of a function
	 */
	public Q EFG(String name){
		Q method = function(name);
		Q efg = edges(XCSG.Contains).forward(method).nodesTaggedWithAny(EVENT_FLOW_NODE).induce(universe().edgesTaggedWithAll(EVENT_FLOW_EDGE));
		return efg;
	}
	
	/**
	 * For a given functions, return the call sites
	 * @param e: The set of functions
	 * @return
	 */
	public AtlasSet<GraphElement> getEventCallSites(AtlasSet<GraphElement> e){
		Q returnsFromE = functionReturn(Common.toQ(Common.toGraph(e)));
		Q callSites = edges(XCSG.DataFlow_Edge).forwardStep(returnsFromE);
		return callSites.eval().leaves(); 
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
				Graph containingMethod = getContainingMethod(element).eval();
				if(containingMethod.nodes().isEmpty() || (!containingMethod.nodes().isEmpty() && !atomicFunctions.contains(containingMethod.nodes().getFirst()))){
					dfForward = dfForward.union(edges(XCSG.DataFlow_Edge).forwardStep(Common.toQ(Common.toGraph(element))));	
				}
			}
			dfForwardPrime = dfForward.eval();
		}while(nodesCount != dfForwardPrime.nodes().size() && edgesCount != dfForwardPrime.edges().size());
		return dfForward;
	}
	
	public Q getContainingMethod(GraphElement e){
		Q declares = edges(XCSG.Contains).reverseStep(Common.toQ(Common.toGraph(e)));
		declares = edges(XCSG.Contains).reverseStep(declares);
		Q nodes = declares.nodesTaggedWithAll(XCSG.Function, "isDef");
		return nodes;
	}
	
	public Q restrictOnControlNodes(Q dataFlow){
		dataFlow = Common.extend(dataFlow, XCSG.Contains);
		return dataFlow.nodesTaggedWithAll(XCSG.ControlFlow_Node);
	}
	
	public Q restrictOnFunctionCalls(Q dataFlow){
		dataFlow = Common.extend(dataFlow, XCSG.Contains);
		return dataFlow.nodesTaggedWithAll(XCSG.Function).induce(edges(XCSG.Call));		
	}
	
	public void verifyProperty(AtlasSet<GraphElement> e1, AtlasSet<GraphElement> e2){
		AtlasSet<GraphElement> eventCallSites = getEventCallSites(e1);
		for(GraphElement callSite : eventCallSites){
			deleteEFGs();
			GraphElement containingMethod = getContainingMethod(callSite).eval().nodes().getFirst();
			if(!containingMethod.attr().get(XCSG.name).equals("dgwrite"))
				continue;
			Q callSiteEvent = Common.toQ(Common.toGraph(callSite));
			Q dataFlow = getForwardDataFlowFromEvent(callSiteEvent);
			Q envelope = restrictOnFunctionCalls(dataFlow).difference(Common.toQ(Common.toGraph(e1)), Common.toQ(Common.toGraph(e2)));
			Q controlNodes = restrictOnControlNodes(dataFlow);
			
			AtlasSet<GraphElement> functions = envelope.nodesTaggedWithAll(XCSG.Function).eval().nodes();
			HashMap<GraphElement, Graph> functionFlowMap = new HashMap<GraphElement, Graph>();
			
			DisplayUtil.displayGraph(envelope.eval(), new Highlighter(), "Envelope");
			
			for(GraphElement function : functions){
				String functionName = function.attr().get(XCSG.name).toString();
				Q cfg = CFG(functionName);
				Q intersection = cfg.intersection(controlNodes);
				H h = new Highlighter(ConflictStrategy.COLOR);
				h.highlight(intersection, java.awt.Color.RED);
				//cfg = Common.extend(cfg, Edge.DECLARES);
				Graph g = cfg.eval();
				DisplayUtil.displayGraph(g, h, "CFG-" + functionName);
				
				GraphUtils.write(Utils.transformAtlasGraph(g, intersection.eval().nodes()),"/home/ahmed/Desktop/cfg-"+functionName+".dot");
				
				Utils.addEFGToIndex(g, Utils.createEventFlowGraph(g, intersection.eval().nodes()));
				Q efg = EFG(functionName);
				
				DisplayUtil.displayGraph(efg.eval(), h, "EFG-" + functionName);
				functionFlowMap.put(function, efg.eval());
			}
			//Verifier verifier = new Verifier(callSite.address().toAddressString(), envelope.eval(), functionFlowMap);
			//verifier.run();
			//break;
		}
	}
	
	public void verifyXinu(){
		verifyProperty(function("getbuf").eval().nodes(), function("freebuf").eval().nodes());
	}
	
	public void analyze(){
		Q get = functionReturn(function("getbuf"));
		Q dfGet = edges(XCSG.DataFlow_Edge).forwardStep(get);
		Graph g = dfGet.eval();
		for(GraphElement e : g.leaves()){
			Q method = getContainingMethod(e);
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
		Q get = functionReturn(function("getbuf"));
		Q dfGet = edges(XCSG.DataFlow_Edge).forwardStep(get);
		Graph g = dfGet.eval();
		Q eprime = null;
		for(GraphElement e : g.leaves()){
			Q method = getContainingMethod(e);
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
				Q method = getContainingMethod(element);
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
			Q cfg = CFG(functionName);
			Q intersection = cfg.intersection(controlNodes);
			H h = new Highlighter(ConflictStrategy.COLOR);
			h.highlight(intersection, java.awt.Color.RED);
			//cfg = Common.extend(cfg, Edge.DECLARES);
			Graph g = cfg.eval();
			DisplayUtil.displayGraph(g, h, "CFG-" + functionName);
			
			Utils.addEFGToIndex(g, Utils.createEventFlowGraph(g, intersection.eval().nodes()));
			Q efg = EFG(functionName);
			
			DisplayUtil.displayGraph(efg.eval(), h, "EFG-" + functionName);
			
			//GraphUtils.write(Utils.transformAtlasGraph(g, intersection.eval().nodes()),"/home/ahmed/Desktop/cfg.dot");
			//GraphUtils.write(Utils.createEventFlowGraph(g, intersection.eval().nodes()),"/home/ahmed/Desktop/efg.dot");
		}
	}

	public Q functionReturn(Q functions) {
		return edges(XCSG.Contains).forwardStep(functions).nodesTaggedWithAll(XCSG.MasterReturn);
	}
}
