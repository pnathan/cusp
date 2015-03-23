/**
 * 
 */
package jasko.tim.lisp.editors.actions;

import jasko.tim.lisp.LispPlugin;
import jasko.tim.lisp.SwankNotFoundException;
import jasko.tim.lisp.editors.LispEditor;
import jasko.tim.lisp.preferences.PreferenceConstants;
import jasko.tim.lisp.views.ReplView;

import org.eclipse.ui.PlatformUI;

/**
 * @author sk
 *
 */
public class EvalTopLevelExpQuickAction extends LispAction {
	
	public EvalTopLevelExpQuickAction(){
	}
	
	public EvalTopLevelExpQuickAction(LispEditor editor) {
		super(editor);
	}

	//FIXME: duplicate code with EvalTopLevelExpAction
	public static void runStr(String exp){
        if (exp.length() == 0) return;

        if( LispPlugin.getDefault().getPreferenceStore()
        		.getBoolean(PreferenceConstants.SHOW_EVAL_IN_REPL) ){
    		try {
    			ReplView repl = (ReplView)PlatformUI.getWorkbench().getActiveWorkbenchWindow()
    				.getActivePage().findView(ReplView.ID);
    			String cleanCmd = exp.replace("\r", "");
    			repl.EvalStateHandle(exp+"\n", cleanCmd);
    			ReplView.switchToRepl();
    		} catch ( Exception e ){
    			try {
               getSwank().sendEval(exp, null);
            }
            catch (SwankNotFoundException e1) {
               // TODO Auto-generated catch block
               e1.printStackTrace();
            }
    		}        	
        } else {
			try {
            getSwank().sendEval(exp, null);
         }
         catch (SwankNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
         }        	
        }
	}
	
	public void run() {
		runStr(getTopLevel());
	}	

}
