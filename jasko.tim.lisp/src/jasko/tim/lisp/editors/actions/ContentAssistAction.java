package jasko.tim.lisp.editors.actions;

import org.eclipse.jface.action.IAction;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IEditorPart;

import jasko.tim.lisp.editors.ILispEditor;
import jasko.tim.lisp.editors.assist.ArglistAssistProcessor;

public class ContentAssistAction extends LispAction {
    private ILispEditor editor;
    
    public ContentAssistAction () {}
    
    public ContentAssistAction (ILispEditor editor) {
        this.editor = editor;
    }
    
    public void setActiveEditor(IAction action, IEditorPart targetEditor) {
       // java.lang.ClassCastException: org.eclipse.ui.internal.ErrorEditorPart cannot be cast to jasko.tim.lisp.editors.ILispEditor

        editor = (ILispEditor)targetEditor;
    }
    
    public void run() {
    	try{
	    	ITextSelection ts = (ITextSelection) editor.getSelectionProvider().getSelection();
	    	if( ArglistAssistProcessor.doArgs(editor.getDocument(),ts.getOffset())){
	            editor.showParameterHints();
	    	} else {
	            editor.showContentCompletions();
	    	}
    	} catch (Throwable e){
    		e.printStackTrace();
    	}
    }
}
