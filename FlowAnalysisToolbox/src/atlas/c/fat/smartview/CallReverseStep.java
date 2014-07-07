package atlas.c.fat.smartview;

import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.atlas.java.core.query.Attr.Edge;
import com.ensoftcorp.atlas.java.core.query.Attr.Node;
import com.ensoftcorp.atlas.java.core.query.Q;
import com.ensoftcorp.atlas.java.core.script.Common;
import com.ensoftcorp.atlas.java.core.script.CommonQueries;
import com.ensoftcorp.atlas.java.core.script.CommonQueries.TraversalDirection;
import com.ensoftcorp.atlas.java.core.script.StyledResult;
import com.ensoftcorp.atlas.java.ui.scripts.selections.AtlasSmartViewScript;

public class CallReverseStep implements AtlasSmartViewScript {

	@Override
	public String[] getSupportedNodeTags() {
		return new String[]{XCSG.Function, XCSG.Method, Node.METHOD};
	}

	@Override
	public String[] getSupportedEdgeTags() {
		return null;
	}

	@Override
	public StyledResult selectionChanged(SelectionInput input) {
		Q interpretedSelection = input.getInterpretedSelection();

		Q res = Common.index().edgesTaggedWithAll(XCSG.Call, Edge.PER_METHOD).reverseStep(interpretedSelection);
		
		return new StyledResult(res);
	}

	@Override
	public String getTitle() {
		return "Call, Reverse Step";
	}
}