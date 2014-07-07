package com.iastate.verifier.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jgrapht.DirectedGraph;
import org.jgrapht.alg.StrongConnectivityInspector;
import org.jgrapht.graph.DefaultEdge;

import com.alexmerz.graphviz.GraphUtils;
import com.alexmerz.graphviz.objects.Edge;
import com.alexmerz.graphviz.objects.Graph;
import com.alexmerz.graphviz.objects.Id;
import com.alexmerz.graphviz.objects.Node;

public class EventFlowGraphCreator {
	
	private Graph flowGraph;
	
	public static String SPECIAL_ID_SUFFIX = "#####";
	
	//private long copyTime = 0;

	public EventFlowGraphCreator(Graph cfg) {
		this.flowGraph = copyGraph(cfg);
	}
	
	public Graph getFlowGraph(){
		return this.flowGraph;
	}
	
	public void toDot(String path){
		GraphUtils.write(this.flowGraph, path);
	}
	
	public void create(){
		//this.removeDuplicatedNodes();
		
		//long startTime = System.currentTimeMillis();
    	
		this.computeEFG();
    	
    	//Config.CFG_TO_EFG_CONVERSION_TIME += (System.currentTimeMillis() - startTime) - copyTime;
	}
	
	private Graph copyGraph(Graph graph){
		//long startTime = System.currentTimeMillis();
		Graph result = Utils.copyGraph(graph);
		//this.copyTime += (System.currentTimeMillis() - startTime);
		return result;
	}
	
	private void computeEFG(){
		// STEPI: Transform the CFG into CDG graph
		Graph G1 = this.flowGraph;
		
		// STEP II: Transform (G1) into a T-irreducible graph (G2)
		Graph G2 = createTIrreducibleGraph(G1);
		
		// STEP III-(a): Compute the subgraph G2' of G2  induced by the non-colored nodes
		Graph G2Prime = removeColoredNodesFromGraph(G2);
		
		// STEP III-(b): Construct the condensation graph G3 of G2'
		HashMap<Node, HashSet<Node>> sccMap = new HashMap<Node, HashSet<Node>>();
		Graph G3 = createCondensationGraph(G2Prime, sccMap);
		
		// STEP IV: Construct (G4) by adding the colored nodes in (G2) to (G3)
		HashMap<String, HashSet<String>> sccMapAsString = this.convertNodeMapToStringMap(sccMap);
		Graph G4 = addColoredNodesToGraph(G2, G3, sccMapAsString);
		
		// STEP V: Transform (G4) into a T-irreducible graph (G5) [Condensed EFG]
		Graph G5 = createTIrreducibleGraph(G4);
		
		// STEP VI: Transform (G5) into (EFG) by expanding each contracted SCC in (G5) by its original nodes from (G3) [EFG]
		Graph EFG = expandSCCs(G2, G5, sccMapAsString);
		
		this.flowGraph = EFG;
		
		
		//Utils.logSCCsStatistics(G2, sccMap.size(), G5, EFG);
	}
	
	private Graph createTIrreducibleGraph(Graph graph){
		Graph tIrreducible = copyGraph(graph);
		
		Node START_NODE = Utils.getEntryExitNodes(tIrreducible, true);
		Node EXIT_NODE = Utils.getEntryExitNodes(tIrreducible, false);
		
		int preEdges = 0, preNodes = 0;
		Node node = null;
		do
		{
			preEdges = tIrreducible.getEdges().size();
			preNodes = tIrreducible.getNodes().size();
			
			for(int i = 0; i < tIrreducible.getNodes().size(); i++){
				node = tIrreducible.getNodes().get(i);
				if(node.equals(START_NODE) || node.equals(EXIT_NODE))
					continue;
				if(!this.isColoredNode(node)){
					if(tIrreducible.getOutDegree(node) == 1){
						this.bypassNode(tIrreducible, node);
						continue;
					}
					List<Node> children = tIrreducible.getChildrenNodes(node);
					if(children.contains(node)){
						this.removeEdge(tIrreducible, node, node);
						continue;
					}
					HashSet<Node> uniqueChildren = new HashSet<Node>();
					for(Node child : children)
						uniqueChildren.add(child);
					if(uniqueChildren.size() == 1){
						this.bypassNode(tIrreducible, node);
						continue;
					}
				}
			}
		}
		while(tIrreducible.getEdges().size() != preEdges || tIrreducible.getNodes().size() != preNodes);
		
		return tIrreducible;
	}
	
	private Graph createCondensationGraph(Graph graph, HashMap<Node, HashSet<Node>> sccMap){
		Graph condensationGraph = copyGraph(graph);
		
		List<HashSet<Node>> maximallySCCs = findMaximallySCCsInGraph(condensationGraph);
		
		for(int index = 0; index < maximallySCCs.size(); index++){
			HashSet<Node> nodes = maximallySCCs.get(index);
			
			Node sccNode = this.createNode(index);
			condensationGraph.addNode(sccNode);
			
			// Map the (sccNode) to its original nodes
			sccMap.put(sccNode, nodes);
		}
		
		for(Node node : sccMap.keySet()){
			
			HashSet<Node> parents = getSCCParentNodes(condensationGraph, node, sccMap);
			for(Node parent : parents){
				condensationGraph.addEdge(parent, node, new Hashtable<String, String>());
			}
			
			HashSet<Node> children = getSCCChildrenNodes(condensationGraph, node, sccMap);
			for(Node child : children){
				condensationGraph.addEdge(node, child, new Hashtable<String, String>());
			}
		}
		
		for(Node node : sccMap.keySet()){
			HashSet<Node> nodes = sccMap.get(node);
			for(Node n : nodes){
				this.removeNode(condensationGraph, n);
			}
		}
		
		return condensationGraph;
	}
	
	private List<HashSet<Node>> findMaximallySCCsInGraph(Graph graph){
		List<HashSet<Node>> maximallySCCs = new ArrayList<HashSet<Node>>();
		
		DirectedGraph<String, DefaultEdge> digraph = GraphUtils.convertToJGraphT(graph);
		
        StrongConnectivityInspector<String, DefaultEdge> inspector = new StrongConnectivityInspector<String, DefaultEdge>(digraph);
        
        List<Set<String>> subgraphs = inspector.stronglyConnectedSets();
        for(Set<String> nodes : subgraphs){
        	if(nodes.size() >= 2){
        		HashSet<Node> maximallySCC = new HashSet<Node>();
        		for(String nodeString : nodes){
        			maximallySCC.add(graph.findNode(nodeString));
        		}
        		maximallySCCs.add(maximallySCC);
        	}
        }
		return maximallySCCs;
	}
	
	private HashSet<Node> getSCCParentNodes(Graph graph, Node scc, HashMap<Node, HashSet<Node>> sccMap){
		HashSet<Node> parents = new HashSet<Node>();
		HashSet<Node> nodes = sccMap.get(scc);
		
		for(Node node : nodes){
			parents.addAll(graph.getParentNodes(node));
		}
		
		HashSet<Node> actualParents = new HashSet<Node>();
		for(Node parent : parents){
			// If a node's parent is within the SCC, ignore it
			if(nodes.contains(parent)){
				continue;
			}
			Node actualParent = getTheContainerSCCForNode(parent, sccMap);
			actualParents.add(actualParent);
		}
		return actualParents;
	}
	
	private HashSet<String> getParentNodesAsString(Graph graph, String node, HashMap<String, HashSet<String>> sccMap){
		List<String> parents = graph.getParentNodes(node);
		
		HashSet<String> actualParents = new HashSet<String>();
		for(String parent : parents){
			String actualParent = getTheContainerSCCForNodeAsString(parent, sccMap);
			actualParents.add(actualParent);
		}
		return actualParents;
	}
	
	private HashSet<Node> getSCCChildrenNodes(Graph graph, Node scc, HashMap<Node, HashSet<Node>> sccMap){
		HashSet<Node> children = new HashSet<Node>();
		HashSet<Node> nodes = sccMap.get(scc);
		
		for(Node node : nodes){
			children.addAll(graph.getChildrenNodes(node));
		}
		
		HashSet<Node> actualChildren = new HashSet<Node>();
		for(Node child : children){
			// If a node's child is within the SCC, ignore it
			if(nodes.contains(child)){
				continue;
			}
			Node actualChild = getTheContainerSCCForNode(child, sccMap);
			actualChildren.add(actualChild);
		}
		return actualChildren;
	}
	
	private HashSet<String> getChildrenNodesAsString(Graph graph, String node, HashMap<String, HashSet<String>> sccMap){
		List<String> children = graph.getChildrenNodes(node);
		
		HashSet<String> actualChildren = new HashSet<String>();
		for(String child : children){
			String actualChild = getTheContainerSCCForNodeAsString(child, sccMap);
			actualChildren.add(actualChild);
		}
		return actualChildren;
	}
	
	private Node getTheContainerSCCForNode(Node node, HashMap<Node, HashSet<Node>> sccMap){
		for(Node scc : sccMap.keySet()){
			HashSet<Node> nodes = sccMap.get(scc);
			if(nodes.contains(node))
				return scc;
		}
		return node;
	}
	
	private String getTheContainerSCCForNodeAsString(String node, HashMap<String, HashSet<String>> sccMap){
		for(String scc : sccMap.keySet()){
			HashSet<String> nodes = sccMap.get(scc);
			if(nodes.contains(node))
				return scc;
		}
		return node;
	}
	
	private Graph addColoredNodesToGraph(Graph originalGraph, Graph condensedGraph, HashMap<String, HashSet<String>> sccMapAsString){
		Graph result = copyGraph(condensedGraph);
		
		// Add colored nodes
		HashSet<Node> coloredNodes = this.getColoredNodes(originalGraph.getNodes());
		for(Node coloredNode : coloredNodes){
			Node newColoredNode = this.createNode(coloredNode.getLabel(), coloredNode.getAttributes());
			result.addNode(newColoredNode);
		}
		
		// Add parent and child edges
		coloredNodes = this.getColoredNodes(result.getNodes());
		for(Node coloredNode : coloredNodes){
			String label = coloredNode.getLabel();
			
			// Get the parent nodes for the coloredNode
			HashSet<String> parents = getParentNodesAsString(originalGraph, label, sccMapAsString);
			for(String parent : parents){
				Node parentNode = result.findNode(parent);
				if(result.findEdge(parentNode, coloredNode) == null)
					result.addEdge(parentNode, coloredNode, new Hashtable<String, String>());
			}
			
			// Get the children nodes for the coloreNode
			HashSet<String> children = getChildrenNodesAsString(originalGraph, label, sccMapAsString);
			for(String child : children){
				Node childNode = result.findNode(child);
				if(result.findEdge(coloredNode, childNode) == null)
					result.addEdge(coloredNode, childNode, new Hashtable<String, String>());
			}
		}
		return result;
	}
	
	private Graph expandSCCs(Graph originalGraph, Graph condensedGraph, HashMap<String, HashSet<String>> sccMapAsString){
		Graph result = copyGraph(condensedGraph);
		
		HashSet<Node> sccNodes = new HashSet<Node>();
		HashSet<Node> addedNodes = new HashSet<Node>();
		for(String scc : sccMapAsString.keySet()){
			Node sccNode = result.findNode(scc);
			if(sccNode == null)
				continue;
			sccNodes.add(sccNode);
			
			for(String node : sccMapAsString.get(scc)){
				Node newNode = this.createNode(node, originalGraph.findNode(node).getAttributes());
				result.addNode(newNode);
				addedNodes.add(newNode);
			}
		}
		
		for(Node node : addedNodes){
			HashSet<Node> parents = getParentsExistingInGraph(originalGraph, result, node.getLabel());
			for(Node parent : parents){
				if(result.findEdge(parent, node) == null)
					result.addEdge(parent, node, new Hashtable<String, String>());
			}
			
			HashSet<Node> children = getChildrenExistingInGraph(originalGraph, result, node.getLabel());
			for(Node child : children){
				if(result.findEdge(node, child) == null)
					result.addEdge(node, child, new Hashtable<String, String>());
			}
		}
		
		for(Node sccNode : sccNodes){
			this.removeNode(result, sccNode);
		}
		
		return result;
	}
	
	private HashSet<Node> getParentsExistingInGraph(Graph originalGraph, Graph newGraph, String node){
		HashSet<Node> actualParents = new HashSet<Node>();
		
		LinkedHashSet<String> parents = new LinkedHashSet<String>(originalGraph.getParentNodes(node));
		for(int i = 0; i < parents.size(); i++){
			String parent = (String) parents.toArray()[i];
			Node nodeInNewGraph = newGraph.findNode(parent);
			if(nodeInNewGraph == null){
				parents.addAll(originalGraph.getParentNodes(parent));
			} else{
				actualParents.add(nodeInNewGraph);
			}
		}
		return actualParents;
	}
	
	private HashSet<Node> getChildrenExistingInGraph(Graph originalGraph, Graph newGraph, String node){
		HashSet<Node> actualChildren = new HashSet<Node>();
		
		LinkedHashSet<String> children = new LinkedHashSet<String>(originalGraph.getChildrenNodes(node));
		
		for(int i = 0; i < children.size(); i++){
			String child = (String) children.toArray()[i];
			Node nodeInNewGraph = newGraph.findNode(child);
			if(nodeInNewGraph == null){
				children.addAll(originalGraph.getChildrenNodes(child));
			} else{
				actualChildren.add(nodeInNewGraph);
			}
		}
		return actualChildren;
	}
	
	/**
	 * Removes all colored (event) nodes in a given graph
	 * @param graph: The graph where the event (colored) nodes will be removed
	 * @return a new graph with no colored (event) nodes
	 */
	private Graph removeColoredNodesFromGraph(Graph graph){
		Graph result = copyGraph(graph);
		
		HashSet<Node> toRemove = this.getColoredNodes(result.getNodes());
		
    	for(Node node : toRemove)
    		this.removeNode(result, node);
    	
    	return result;
	}
	
	private HashSet<Node> getColoredNodes(List<Node> nodes){
		HashSet<Node> result = new HashSet<Node>();
		for(Node node :  nodes){
			if(isColoredNode(node))
				result.add(node);
		}
		return result;
	}
	
	/**
	 * Removes all duplicated nodes in the tracking process in the CFG
	 */
	private void removeDuplicatedNodes(){
    	HashSet<Node> toRemove = new HashSet<Node>();
    	for(Node node : this.flowGraph.getNodes()){
    		if(node.getLabel().endsWith("_T"))
    			toRemove.add(node);
    	}
    	
    	for(Node node : toRemove)
    		this.removeNode(this.flowGraph, node);
	}
	
	/**
	 * Removes a node from the passed graph
	 * @param graph: The graph where the node will be removed from
	 * @param node: The node to be removed
	 */
	private void removeNode(Graph graph, Node node){
		for(Node parent : graph.getParentNodes(node))
			this.removeEdge(graph, parent, node);
		for(Node child : graph.getChildrenNodes(node))
			this.removeEdge(graph, node, child);
		graph.getNodes().remove(node);	
	}
	
	@SuppressWarnings("unchecked")
	private void bypassNode(Graph graph, Node node){
		List<Node> parents = graph.getParentNodes(node);
		List<Node> children = graph.getChildrenNodes(node);
		
		Hashtable<String, String> attributes = null;
		Edge edge = null;
		for(Node parent : parents){
			edge = graph.findEdge(parent, node);
			attributes = (Hashtable<String, String>) edge.getAttributes().clone();
			for(Node child : children){
				if(graph.findEdge(parent, child) == null)
					this.addEdge(parent, child, attributes, graph);
			}
		}
		
		this.removeNode(graph, node);
	}
	
	/**
	 * Creates a new node with passed identifier
	 * @param identifier: A unique Id for the newly created node
	 * @return A new node with an Id (identifier)
	 */
	private Node createNode(int identifier){
    	return this.createNode(SPECIAL_ID_SUFFIX + String.valueOf(identifier), null);
	}
	
	private Node createNode(String label, Hashtable<String, String> attrs){
    	Node newNode = new Node();
    	Id id = new Id();
    	id.setLabel(label);
    	newNode.setId(id);
    	if(attrs != null){
    		for(String attribute : attrs.keySet())
    			newNode.setAttribute(attribute, attrs.get(attribute));
    	}
    	return newNode;
	}
	
	/**
	 * Removes an edge from a the passed graph
	 * @param graph: The graph where the edge will be removed from
	 * @param from: The (from) node where the edge is out from
	 * @param to: The (to) node where the edges is in to
	 */
	private void removeEdge(Graph graph, Node from, Node to){
		HashSet<Edge> edges = new HashSet<Edge>();
		for(Edge e : graph.getEdges()){
			if(e.getSource().getNode().equals(from) && e.getTarget().getNode().equals(to))
				edges.add(e);
		}
		for(Edge e : edges)
			graph.getEdges().remove(e);
	}
	
	private void addEdge(Node source, Node target, Hashtable<String, String> attrs, Graph g){
		g.addEdge(source, target, attrs);
	}
	
	/**
	 * Return whether a node is a colored (i.e., event) node
	 * @param node: a node to check for the colored property
	 * @return true: when the node is a colored (event) node, otherwise false
	 */
	private boolean isColoredNode(Node node){
		Hashtable<String, String> attriutes = node.getAttributes();
    	if(attriutes.containsKey("fillcolor") && attriutes.containsKey("style") && attriutes.get("style").equals("filled"))
    		return true;
    	return false;
	}
	
	private HashMap<String, HashSet<String>> convertNodeMapToStringMap(HashMap<Node, HashSet<Node>> map){
		HashMap<String, HashSet<String>> result = new HashMap<String, HashSet<String>>();
		for(Node sccNode : map.keySet()){
			HashSet<String> nodeStrings = new HashSet<String>();
			for(Node node : map.get(sccNode))
				nodeStrings.add(node.getLabel());
			result.put(sccNode.getLabel(), nodeStrings);
		}
		return result;
	}
}
