package jasko.tim.lisp.editors.assist;

import jasko.tim.lisp.LispPlugin;
import jasko.tim.lisp.SwankNotFoundException;
import jasko.tim.lisp.editors.ILispEditor;
import jasko.tim.lisp.editors.LispEditor;
import jasko.tim.lisp.preferences.PreferenceConstants;
import jasko.tim.lisp.swank.LispNode;
import jasko.tim.lisp.swank.LispParser;
import jasko.tim.lisp.swank.SwankInterface;
import jasko.tim.lisp.util.LispUtil;

import java.util.ArrayList;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.jface.text.contentassist.CompletionProposal;
import org.eclipse.jface.text.contentassist.ContextInformation;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.contentassist.IContextInformationPresenter;
import org.eclipse.jface.text.contentassist.IContextInformationValidator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;

/**
 * One of the more important parts of an IDE, IMO. This class provides both
 * symbol completion and context info.
 * 
 * @author Tim Jasko
 * 
 */
public class ArglistAssistProcessor implements IContentAssistProcessor {

   /**
    * Tells Eclipse when the given context info is no longer valid. Also
    * provides text formatting for the tooltip.
    * 
    * @author Tim
    * 
    */
   private class ArglistContext implements IContextInformationValidator,
            IContextInformationPresenter {
      private IContextInformation info;
      private int                 offset;
      private ITextViewer         viewer;

      @Override
      public void install(final IContextInformation info,
                          final ITextViewer viewer,
                          final int offset) {
         this.viewer = viewer;
         this.info = info;
         this.offset = offset;
      }

      @Override
      public boolean isContextInformationValid(final int offset) {
         if (offset < this.offset) {
            return false;
         }
         try {
            final char c = this.viewer.getDocument().getChar(offset - 1);
            if ((c == '(') || (c == ')')) {
               return false;
            }
            if ( !ArglistAssistProcessor.this.makeInstanceInfoFound &&
                     Character.isWhitespace(c) &&
                     LispUtil.getCurrentFunction(this.viewer.getDocument(),
                                                 offset)
                             .equals("make-instance")) {
               return false;
            }
            if ( !ArglistAssistProcessor.this.defmethodInfoFound &&
                     Character.isWhitespace(c) &&
                     LispUtil.getCurrentFunction(this.viewer.getDocument(),
                                                 offset).equals("defmethod")) {
               return false;
            }
         }
         catch (final BadLocationException e) {

         }
         return true;
      }

      @Override
      public boolean updatePresentation(final int offset,
                                        final TextPresentation pres) {
         final String display = this.info.getInformationDisplayString();
         final LispNode stuff = LispParser.parse(display, true);
         // Without the check, sometimes the same range gets added twice for
         // some reason, which causes an
         // IllegalArgumentException later in StyledText.setStyleRanges because
         // of overlap
         if ((pres.getCoverage() == null) ||
                  (pres.getCoverage().getLength() == 0)) {
            pres.addStyleRange(new StyleRange(0,
                                              Math.min(stuff.endOffset + 1,
                                                       display.length()),
                                              null,
                                              null,
                                              SWT.BOLD));
         }
         return true;
      }
   }

   public static final char[]  completionActivators = "qwertyuiopasdfghjklzxcvbnm*!-:".toCharArray();
   private static final char[] infoActivators       = new char[] { ' ' };
   private static String       NO_DOC_STRING        = "No additional information is available";
   private static final int    TIMEOUT              = 2000;

   /**
    * Finds if offset is after space or ), so that argHint should be displayed
    * 
    * @param doc
    * @param offset
    * @return
    */
   public static boolean doArgs(final IDocument doc, final int offset) {
      char c;
      try {
         c = doc.getChar(offset - 1);
      }
      catch (final BadLocationException e) {
         e.printStackTrace();
         return false;
      }
      try {
         if (((c == ')') || Character.isWhitespace(c)) &&
                  doc.getPartition(offset - 1)
                     .getType()
                     .equals(IDocument.DEFAULT_CONTENT_TYPE)) {
            return true;
         }
         else {
            return false;
         }
      }
      catch (final BadLocationException e) {
         e.printStackTrace();
         return false;
      }
   }

   protected boolean  defmethodInfoFound     = false;

   /**
    * Lets our ContextValidator know whether or not it needs to dismiss the
    * make-instance special tooltip
    */
   protected boolean  makeInstanceInfoFound  = false;

   private LispEditor editor;

   // variable
   // name
   private String[]   lastCompletionResults;

   private String[]   lastCompletionResultsInfo;

   private String     lastCompletionVariable = ")";  // impossible

   // *****************************************
   // Context Info

   public ArglistAssistProcessor(final ILispEditor editor) {
      if (editor instanceof LispEditor) {
         this.editor = (LispEditor) editor;
      }
      else {
         this.editor = null;
      }
   }

   @Override
   public ICompletionProposal[] computeCompletionProposals(final ITextViewer viewer,
                                                           final int offset) {

      final String variable = LispUtil.getCurrentWord(viewer.getDocument(),
                                                      offset);

      if (variable.equals("") || variable.contains("\"")) {
         return null;
      }

      final IPreferenceStore prefs = LispPlugin.getDefault()
                                               .getPreferenceStore();
      int nn = prefs.getInt(PreferenceConstants.AUTO_COMPLETIONS_NLIMIT);
      final boolean doGetDocs = prefs.getBoolean(PreferenceConstants.DOCS_IN_COMPLETIONS);

      // Optimization! Save us lots of swanking (when completion is not fuzzy)
      if (variable.startsWith(this.lastCompletionVariable) &&
               !variable.contains(":") &&
               !prefs.getBoolean(PreferenceConstants.AUTO_FUZZY_COMPLETIONS)) {
         if (0 == nn) {
            nn = this.lastCompletionResults.length;
         }
         else {
            nn = Math.min(nn, this.lastCompletionResults.length);
         }
         final ArrayList<ICompletionProposal> temp = new ArrayList<ICompletionProposal>(nn);
         final int rStart = offset - variable.length();
         for (int i = 0; i < this.lastCompletionResults.length; ++i) {
            final String comp = this.lastCompletionResults[i];
            if (comp.startsWith(variable)) {
               String info = null;
               IContextInformation ci = null;
               if (doGetDocs && (this.lastCompletionResultsInfo != null)) {
                  info = this.lastCompletionResultsInfo[i];
                  ci = getContextInfo(info);
               }
               temp.add(new CompletionProposal(comp,
                                               rStart,
                                               variable.length(),
                                               comp.length(),
                                               null,
                                               null,
                                               ci,
                                               info));
               if (temp.size() >= nn) {
                  break;
               }
            }
         }

         // Displaying a completion for something that is already complete is
         // dumb.
         if (temp.size() == 1) {
            if (temp.get(0).getDisplayString().equals((variable))) {
               return null;
            }
         }

         final ICompletionProposal[] ret = new ICompletionProposal[temp.size()];
         for (int i = 0; i < temp.size(); ++i) {
            ret[i] = temp.get(i);
         }
         return ret;
      }
      else {
         SwankInterface swank;
         try {
            swank = LispPlugin.getDefault().getSwank();
         }
         catch (SwankNotFoundException e) {
            e.printStackTrace();
            // Return a zero-length completion proposal
            return new ICompletionProposal[0];
         }

         if (doGetDocs) {
            String usepkg = null;
            if (this.editor != null) {
               usepkg = LispUtil.getPackage(viewer.getDocument().get(), offset);
            }
            final String[][] results = swank.getCompletionsAndDocs(variable,
                                                                   usepkg,
                                                                   ArglistAssistProcessor.TIMEOUT,
                                                                   nn);
            if (null == results) {
               return null;
            }
            if (results[0].length == 1) {
               if (results[0][0].equals(variable)) {
                  return null;
               }
            }
            if (0 == nn) {
               nn = results[0].length;
            }
            else {
               nn = Math.min(nn, results[0].length);
            }

            final ICompletionProposal[] ret = new ICompletionProposal[nn];
            final int rStart = offset - variable.length();
            this.lastCompletionResultsInfo = results[1];
            this.lastCompletionVariable = variable;
            this.lastCompletionResults = results[0];
            for (int j = 0; j < results[0].length; ++j) {
               final String info = results[1][j];
               if (info.equals("")) {
                  this.lastCompletionResultsInfo[j] = ArglistAssistProcessor.NO_DOC_STRING;
               }
               else {
                  this.lastCompletionResultsInfo[j] = info;
               }
               if (j < nn) {
                  final IContextInformation ci = getContextInfo(info);
                  ret[j] = new CompletionProposal(results[0][j].toLowerCase(),
                                                  rStart,
                                                  variable.length(),
                                                  results[0][j].length(),
                                                  null,
                                                  null,
                                                  ci,
                                                  this.lastCompletionResultsInfo[j]);
               }
            }
            return ret;

         }
         else {
            final String[] results = (this.editor != null ? swank.getCompletions(variable,
                                                                                 LispUtil.getPackage(viewer.getDocument()
                                                                                                           .get(),
                                                                                                     offset),
                                                                                 ArglistAssistProcessor.TIMEOUT)
                     : swank.getCompletions(variable,
                                            ArglistAssistProcessor.TIMEOUT));
            if (results.length == 1) {
               if (results[0].equals(variable)) {
                  return null;
               }
            }

            if (0 == nn) {
               nn = results.length;
            }
            else {
               nn = Math.min(nn, results.length);
            }

            final ICompletionProposal[] ret = new ICompletionProposal[nn];
            final int rStart = offset - variable.length();
            for (int j = 0; j < nn; ++j) {
               ret[j] = new CompletionProposal(results[j].toLowerCase(),
                                               rStart,
                                               variable.length(),
                                               results[j].length());
            }

            this.lastCompletionVariable = variable;
            this.lastCompletionResults = results;
            return ret;
         }
      }
   }

   @Override
   public IContextInformation[] computeContextInformation(final ITextViewer viewer,
                                                          final int offset) {

      final String info = computeContextInfoString(viewer.getDocument(), offset);

      if ((info != null) && !info.equals("") && !info.equals("nil")) {
         return new IContextInformation[] { new ContextInformation(info, info) };
      }
      return null;
   }

   public String computeContextInfoString(final IDocument doc, final int offset) {
      final String function = LispUtil.getCurrentFunction(doc, offset);

      SwankInterface swank;
      try {
         swank = LispPlugin.getDefault().getSwank();
      }
      catch (SwankNotFoundException e) {
         e.printStackTrace();
         return "";
      }

      String info = "";

      // Special arglist assistance for make-instance
      if (function.equals("make-instance")) {
         final LispNode exp = LispParser.parse(LispUtil.getCurrentUnfinishedExpression(doc,
                                                                                       offset));
         System.out.println("*" + exp);
         if (exp.get(0).params.size() >= 2) {
            String className = exp.get(0).params.get(1).value;
            className = className.replaceFirst("\'", "");
            System.out.println("className:" + className);

            if (this.editor != null) {
               info = swank.getMakeInstanceArglist(className,
                                                   LispUtil.getPackage(doc.get(),
                                                                       offset),
                                                   ArglistAssistProcessor.TIMEOUT);
            }
            else {
               info = swank.getMakeInstanceArglist(className,
                                                   ArglistAssistProcessor.TIMEOUT);
            }
            this.makeInstanceInfoFound = true;
         }
      }
      else
         if (function.equals("defmethod")) {
            final LispNode exp = LispParser.parse(LispUtil.getCurrentUnfinishedExpression(doc,
                                                                                          offset));
            if (exp.get(0).params.size() >= 2) {
               final String arg0 = exp.get(0).params.get(1).value;

               if (this.editor != null) {
                  info = swank.getSpecialArglist("defmethod",
                                                 arg0,
                                                 LispUtil.getPackage(doc.get(),
                                                                     offset),
                                                 ArglistAssistProcessor.TIMEOUT);
               }
               else {
                  info = swank.getSpecialArglist("defmethod",
                                                 arg0,
                                                 ArglistAssistProcessor.TIMEOUT);
               }
               this.defmethodInfoFound = true;
               System.out.println("info:" + info);
            }
         }

      if (info.equals("")) {
         this.makeInstanceInfoFound = false;
         this.defmethodInfoFound = false;
         String docString = "";
         if (this.editor == null) {
            info = swank.getArglist(function, 3000);
            docString = swank.getDocumentation(function, 1000);
         }
         else {
            info = swank.getArglist(function,
                                    3000,
                                    LispUtil.getPackage(doc.get(), offset));
            docString = swank.getDocumentation(function,
                                               LispUtil.getPackage(doc.get(),
                                                                   offset),
                                               1000);
         }
         if ( !docString.equals("")) {
            final String[] lines = docString.split("\n");
            final int maxlines = LispPlugin.getDefault()
                                           .getPreferenceStore()
                                           .getInt(PreferenceConstants.MAX_HINT_LINES);
            if ((maxlines > 0) && (lines.length > maxlines)) {
               for (int i = 0; i < maxlines; ++i) {
                  info += "\n" + lines[i];
               }
               info += "...";
            }
            else {
               info += "\n" + docString;
            }
         }
      }
      return info;
   }

   // TODO Use swank:documentation-symbol to get info where available
   // swank:describe-symbol ain't bad either

   @Override
   public char[] getCompletionProposalAutoActivationCharacters() {
      return ArglistAssistProcessor.completionActivators;
   }

   @Override
   public char[] getContextInformationAutoActivationCharacters() {
      return ArglistAssistProcessor.infoActivators;
   }

   @Override
   public IContextInformationValidator getContextInformationValidator() {
      return new ArglistContext();
   }

   @Override
   public String getErrorMessage() {
      return null;
   }

   private IContextInformation getContextInfo(final String info) {
      if ((info != null) && !info.equals("") &&
               !info.equals(ArglistAssistProcessor.NO_DOC_STRING)) {
         return new ContextInformation(info, info);
      }
      else {
         return null;
      }
   }

}
