package com.iastate.atlas.scripts;

import static com.ensoftcorp.atlas.java.core.script.Common.universe;
import static com.ensoftcorp.atlas.java.core.script.CommonQueries.methodParameter;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import atlas.c.scripts.Queries;

import com.ensoftcorp.atlas.c.core.query.Attr.Edge;
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
import com.iastate.verifier.main.Verifier;

public class LinuxScripts {
	
	public static String DUPLICATE_NODE = "duplicateNode";
	public static String DUPLICATE_EDGE = "duplicateEdge";
	
	public Queries query = new Queries();

	public Q compute(int step){
		
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
		
		HashSet<String> specials = new HashSet<String>();
		specials.add("kmalloc");
		//specials.add("kfree");
		
		AtlasSet<GraphElement> e3 = new AtlasHashSet<GraphElement>();
		for(String u : specials){
			e3.addAll(query.function(u).eval().nodes());
		}
		Q specialFunctions = Common.toQ(Common.toGraph(e3));
				
		Q params = methodParameter(e1Functions.union(e2Functions), 0);
		
		Q dfReverseParams = universe().edgesTaggedWithAny(XCSG.DataFlow_Edge, com.ensoftcorp.atlas.c.core.query.Attr.Edge.ADDRESS_OF, com.ensoftcorp.atlas.c.core.query.Attr.Edge.POINTER_DEREFERENCE).reverseStep(params);
		Q rev = universe().edgesTaggedWithAny(XCSG.DataFlow_Edge, com.ensoftcorp.atlas.c.core.query.Attr.Edge.ADDRESS_OF, com.ensoftcorp.atlas.c.core.query.Attr.Edge.POINTER_DEREFERENCE).reverse(params);
		
		Q mutexTypes = universe().nodesTaggedWithAll(XCSG.C.Struct).selectNode(XCSG.name, "struct mutex");
		Q signatures = universe().edgesTaggedWithAny(XCSG.TypeOf, Edge.ELEMENTTYPE, "arrayOf", "pointerOf").reverse(mutexTypes);
		signatures = signatures.roots().nodesTaggedWithAll(XCSG.Variable);
		
		int count = -1;
		for(GraphElement mutexObject : signatures.eval().nodes()){
			String name = (String) mutexObject.attr().get(XCSG.name);
			//if(name.contains("band"))
			{
				Q m = Common.toQ(Common.toGraph(mutexObject));
				Q returns = query.functionReturn(specialFunctions);
				Q df = rev.between(m, params, returns);
				
				if(df.eval().nodes().isEmpty())
					continue;
				if(++count == step){
					
					DisplayUtil.displayGraph(Common.extend(m, XCSG.Contains).eval(), new Highlighter(), "Mutex Object");
					
					DisplayUtil.displayGraph(Common.extend(df, XCSG.Contains).eval(), new Highlighter(), "Data Flow Graph");
					
					Q dfParams = df.intersection(dfReverseParams).roots();
					Q controlFlowNodes = universe().edgesTaggedWithAll(XCSG.Contains).reverseStep(dfParams).nodesTaggedWithAll(XCSG.ControlFlow_Node);
					//DisplayUtil.displayGraph(Common.extend(controlFlowNodes, XCSG.Contains).eval(), new Highlighter(), "Control Nodes Graph");
					
					Q callSites = universe().edgesTaggedWithAll(XCSG.Contains).forward(controlFlowNodes).nodesTaggedWithAll(XCSG.CallSite);
					//DisplayUtil.displayGraph(Common.extend(callSites, XCSG.Contains).eval(), new Highlighter(), "Call Sites Graph");
					
					Q mpg = query.mpg(callSites, locks, unlocks);
					
					mpg = mpg.union(e1Functions, e2Functions);
					mpg = mpg.induce(universe().edgesTaggedWithAll(XCSG.Call));
					
					Q unused = mpg.roots().intersection(mpg.leaves());
					mpg = mpg.difference(unused);
					
					verify(mutexObject, mpg, controlFlowNodes, e1, e2, locks, unlocks);
					break;
				}
			}
		}
		return universe().empty();
	}
	
	public void verify(GraphElement object, Q envelope, Q mainEventNodes, AtlasSet<GraphElement> e1, AtlasSet<GraphElement> e2, HashSet<String> e1Functions, HashSet<String> e2Functions){
		query.deleteEFGs();
		deleteDuplicateNodes();
		
		HashMap<GraphElement, Graph> functionFlowMap = new HashMap<GraphElement, Graph>();
		HashMap<GraphElement, List<Q>> functionEventsMap = new HashMap<GraphElement, List<Q>>();
		
		Graph envelopeGraph = envelope.eval();
		DisplayUtil.displayGraph(Common.extend(envelope, XCSG.Contains).eval(), new Highlighter(), "Envelope");
		
		HashSet<String> envelopeFunctions = new HashSet<String>();
		for(GraphElement function : envelopeGraph.nodes()){
			if(e1.contains(function) || e2.contains(function))
				continue;
			
			String functionName = function.attr().get(XCSG.name).toString();
			envelopeFunctions.add(functionName);
		}
		
		for(GraphElement function : envelopeGraph.nodes()){
			if(e1.contains(function) || e2.contains(function))
				continue;
			
			String functionName = function.attr().get(XCSG.name).toString();
			Q cfg = query.CFG(functionName);
			List<Q> events = getEventNodes(cfg, mainEventNodes, envelopeFunctions, e1Functions, e2Functions);
			Q e1Events = events.get(0);
			Q e2Events = events.get(1);
			Q envelopeEvents = events.get(2);
			Q eventNodes = events.get(3); 
			
			H h = new Highlighter(ConflictStrategy.COLOR);
			h.highlight(e1Events, Color.RED);
			h.highlight(e2Events, Color.GREEN);
			h.highlight(envelopeEvents, Color.BLUE);

			Graph g = cfg.eval();
			//DisplayUtil.displayGraph(Common.extend(cfg, XCSG.Contains).eval(), h, "CFG-" + functionName);
			
			Utils.addEFGToIndex(function, g, Utils.createEventFlowGraph(g, eventNodes.eval().nodes()));
			
			Q efg = query.EFG(functionName);
			DisplayUtil.displayGraph(Common.extend(efg, XCSG.Contains).eval(), h, "EFG-" + functionName);
			
			functionFlowMap.put(function, efg.eval());
			events.remove(events.size() - 1);
			functionEventsMap.put(function, events);
		}
		envelope = envelope.difference(envelope.leaves());
		String signature = object.attr().get(XCSG.name) + "(" + object.address().toAddressString() + ")";
		Verifier verifier = new Verifier(signature, envelope.eval(), functionFlowMap, functionEventsMap);
		verifier.run();
	}
	
	public List<Q> getEventNodes(Q cfg, Q mainEventNodes, HashSet<String> envelopeFunctions, HashSet<String> e1, HashSet<String> e2){
		Q controlFlowNodes = cfg.nodesTaggedWithAll(XCSG.ControlFlow_Node);
		Q callSites = universe().edgesTaggedWithAll(XCSG.Contains).forward(controlFlowNodes).nodesTaggedWithAll(XCSG.CallSite);
		AtlasSet<GraphElement> nodes = callSites.eval().nodes();
		
		Q e1Events = Common.empty();
		Q e2Events = Common.empty();
		Q envelopeEvents = Common.empty();
		
		AtlasSet<GraphElement> mainControlFlowNodes = mainEventNodes.eval().nodes();
		for(GraphElement node : nodes){
			Q controlFlowNode = universe().edgesTaggedWithAll(XCSG.Contains).reverseStep(Common.toQ(Common.toGraph(node))).nodesTaggedWithAll(XCSG.ControlFlow_Node);
			if(mainControlFlowNodes.contains(controlFlowNode.eval().nodes().getFirst())){
				if(query.isCalling(node, e1)){
					e1Events = e1Events.union(controlFlowNode);
				}
				
				if(query.isCalling(node, e2)){
					e2Events = e2Events.union(controlFlowNode);
				}
			}
			
			if(query.isCalling(node, envelopeFunctions)){
				envelopeEvents = envelopeEvents.union(controlFlowNode);
			}
		}
		List<Q> result = new ArrayList<Q>();
		result.add(e1Events);
		result.add(e2Events);
		result.add(envelopeEvents);
		result.add(e1Events.union(e2Events, envelopeEvents));
		return result;
	}
	
	public void deleteDuplicateNodes(){
		AtlasSet<GraphElement> edges = universe().edgesTaggedWithAll(DUPLICATE_EDGE).eval().edges();
		HashSet<GraphElement> toDelete = new HashSet<GraphElement>(); 
		for(GraphElement edge : edges){
			toDelete.add(edge);
		}
		
		for(GraphElement edge : toDelete){
			Graph.U.delete(edge);
		}
		
		AtlasSet<GraphElement> nodes = universe().nodesTaggedWithAll(DUPLICATE_NODE).eval().nodes();
		
		toDelete = new HashSet<GraphElement>(); 
		for(GraphElement node : nodes){
			toDelete.add(node);
		}
		
		for(GraphElement node : toDelete){
			Graph.U.delete(node);
		}
	}
}
