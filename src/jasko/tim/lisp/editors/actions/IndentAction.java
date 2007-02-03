package jasko.tim.lisp.editors.actions;

import jasko.tim.lisp.util.*;
import jasko.tim.lisp.editors.assist.*;


import org.eclipse.jface.action.*;
import org.eclipse.jface.text.*;
import org.eclipse.jface.viewers.*;
import org.eclipse.ui.*;
import org.eclipse.ui.texteditor.*;

/**
 * This is probably not the right place to do indentation, but it's a lot easier to do it here than in
 *  IAutoEditStrategy.
 * If somebody has a better understanding of these things, feel free to implement this in a more
 *  proper fashion.
 * @author Tim Jasko
 * @see jasko.tim.lisp.editors.assist.LispIndenter
 */
public class IndentAction extends Action implements IEditorActionDelegate {
	private AbstractTextEditor editor;
	
	public IndentAction() {
	}
	
	public IndentAction(AbstractTextEditor editor) {
		this.editor = editor;
	}

	public void setActiveEditor(IAction action, IEditorPart targetEditor) {
		editor = (AbstractTextEditor) targetEditor;
	}
	
	public void run() {
		ITextSelection ts = (ITextSelection) editor.getSelectionProvider().getSelection();
		int offset = ts.getOffset();
		IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());

		try {
			int firstLine = doc.getLineOfOffset(offset);
			int lastLine = doc.getLineOfOffset(offset + ts.getLength());
			for (int funcLine = firstLine; funcLine <= lastLine; ++funcLine) {
				IRegion lineInfo = doc.getLineInformation(funcLine);
				
				LispUtil.FunctionInfo fi = LispUtil.getCurrentFunctionInfo(doc, lineInfo.getOffset());
				
				String indent = LispIndenter.calculateIndent(fi, doc);
				String line = doc.get(lineInfo.getOffset(), lineInfo.getLength());
				
				String newLine = indent + line.trim();
				doc.replace(lineInfo.getOffset(), lineInfo.getLength(), newLine);
				
			}
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
	}

	public void run(IAction action) {
		run();
	}
	

	public void selectionChanged(IAction action, ISelection selection) {

	}

}
