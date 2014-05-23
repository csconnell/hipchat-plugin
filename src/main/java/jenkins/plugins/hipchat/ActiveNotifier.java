package jenkins.plugins.hipchat;

import hudson.Util;
import hudson.model.*;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.ChangeLogSet.Entry;
import org.apache.commons.lang.StringUtils;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

@SuppressWarnings("rawtypes")
public class ActiveNotifier implements FineGrainedNotifier {

    // Logger
    private static final Logger logger = Logger.getLogger(HipChatListener.class.getName());

    // Hipchat notifier
    static HipChatNotifier notifier;
    
    
    public ActiveNotifier(HipChatNotifier notifier) {
        super();
        ActiveNotifier.notifier = notifier;
    }

    
    private HipChatService getHipChat(AbstractBuild r) {
        AbstractProject<?, ?> project = r.getProject();
        String projectRoom = Util.fixEmpty(project.getProperty(HipChatNotifier.HipChatJobProperty.class).getRoom());
        return notifier.newHipChatService(projectRoom);
    }


    public void deleted(AbstractBuild r) {
    }

    // The build has started, get changes and cause then communicate.
    public void started(AbstractBuild build) {
        String changes = getChanges(build);
        CauseAction cause = build.getAction(CauseAction.class);

        
        MessageBuilder message = new MessageBuilder(notifier, build);        
        // If we have changes, then publish a start notification with the changes.
        if (changes != null) {
            message.append(changes);
            // Add an open link to the end of the message.
            message.appendOpenLink();
            
        // If we don't have changes, but we do have a cause, then start building
        // a message and send the build cause plus a link
        } else if (cause != null) {
            //MessageBuilder message = new MessageBuilder(notifier, build);
            message.append(cause.getShortDescription());
         // Add an open link to the end of the message.
            message.appendOpenLink();
            
        // Otherwise, just get the build status message and send it along
        } else {
            message.append(getBuildStatusMessage(build));
            
        }
        
        // Publish the start notification (if desired)
        notifyStart(build, message.toString());
    }

    private void notifyStart(AbstractBuild build, String message) {
        getHipChat(build).publish(message, "green");
    }

    public void finalized(AbstractBuild r) {
    }

    public void completed(AbstractBuild r) {
        AbstractProject<?, ?> project = r.getProject();
        HipChatNotifier.HipChatJobProperty jobProperty = project.getProperty(HipChatNotifier.HipChatJobProperty.class);
        Result result = r.getResult();
        AbstractBuild<?, ?> previousBuild = project.getLastBuild().getPreviousBuild();
        Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
        if ((result == Result.ABORTED && jobProperty.getNotifyAborted())
                || (result == Result.FAILURE && jobProperty.getNotifyFailure())
                || (result == Result.NOT_BUILT && jobProperty.getNotifyNotBuilt())
                || (result == Result.SUCCESS && previousResult == Result.FAILURE && jobProperty.getNotifyBackToNormal())
                || (result == Result.SUCCESS && jobProperty.getNotifySuccess())
                || (result == Result.UNSTABLE && jobProperty.getNotifyUnstable())) {
            
            // Publish the desired messages
            getHipChat(r).publish(getBuildStatusMessage(r), getBuildColor(r));
            
            /*
             * Here we are sending a second message to alert either the build
             * launcher (the builder) or the committers to let them know the status
             * of the build.
             */
            // Get the committers, if there are changes ...
            String fullBuildName = r.getFullDisplayName();            
            if ( getChanges(r) != null && jobProperty.getMentionCommitters())
            {
                getHipChat(r).publishText(String.format("%s - Your commits were included in build for %s, which had a status of %s.  Go to %s to check out the details", 
                        getCommitAuthors(r), 
                        fullBuildName,
                        result.toString(),
                        notifier.getBuildServerUrl() + r.getUrl())
                        , getBuildColor(r));
            }
            // ... otherwise we capture who launched the build
            else if (jobProperty.getMentionBuilders())
            {
                // The message coming from Jenkins is "started by user <username>".
                String builder = r.getAction(CauseAction.class).getShortDescription().substring(16);
                
                // If we actually know the builder's name, then @mention them
                if (!builder.equalsIgnoreCase("anonymous"))
                {
                    getHipChat(r).publishText(String.format("@%s - You launched a build for %s with a result of %s.  Go to %s to check out the details", 
                            builder,
                            fullBuildName,
                            result.toString(),
                            notifier.getBuildServerUrl() + r.getUrl())
                            , getBuildColor(r));
                }
                // If they didn't log in, then chastise them!
                else
                {
                    getHipChat(r).publishText(String.format("A build was started by %s - it would be better if you log in to Jenkins next time!!", builder), getBuildColor(r));
                }
            }
        }
        
    }

    static String getChanges(AbstractBuild r) {
        if (!r.hasChangeSetComputed()) {
            logger.info("No change set computed...");
            return null;
        }
        ChangeLogSet changeSet = r.getChangeSet();
        List<Entry> entries = new LinkedList<Entry>();
        Set<AffectedFile> files = new HashSet<AffectedFile>();
        for (Object o : changeSet.getItems()) {
            Entry entry = (Entry) o;
            logger.info("Entry " + o);
            entries.add(entry);
            files.addAll(entry.getAffectedFiles());
        }
        if (entries.isEmpty()) {
            logger.info("Empty change...");
            return null;
        }
        
        Set<String> authors = new HashSet<String>();
        for (Entry entry : entries) {
            authors.add(entry.getAuthor().getDisplayName());
        }
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.append("- changes from ");
        message.append(StringUtils.join(authors, ", "));
        message.append(" (");
        message.append(files.size());
        message.append(" file(s) changed)");
        //return message.appendOpenLink().toString();
        return message.toString();
    }
    static String getCommitAuthors(AbstractBuild build)
    {
        ChangeLogSet changeSet = build.getChangeSet();
        List<Entry> entries = new LinkedList<Entry>();
        Set<AffectedFile> files = new HashSet<AffectedFile>();
        for (Object o : changeSet.getItems()) {
            Entry entry = (Entry) o;
            logger.info("Entry " + o);
            entries.add(entry);
            files.addAll(entry.getAffectedFiles());
        }
        if (entries.isEmpty()) {
            logger.info("Empty change...");
            return null;
        }
        
        Set<String> authors = new HashSet<String>();
        for (Entry entry : entries) {
            authors.add('@' + entry.getAuthor().getDisplayName());
        }
        
        return StringUtils.join(authors, ", ");
    }
    
    
    static String getBuildColor(AbstractBuild r) {
        Result result = r.getResult();
        if (result == Result.SUCCESS) {
            return "green";
        } else if (result == Result.FAILURE) {
            return "red";
        } else {
            return "yellow";
        }
    }

    String getBuildStatusMessage(AbstractBuild r) {
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.appendStatusMessage();
        message.appendDuration();
        message.appendOpenLink().toString();
        message.appendDetails();
        return message.toString();
    }

    public static class MessageBuilder {
        private StringBuffer message;
        private HipChatNotifier notifier;
        private AbstractBuild build;

        public MessageBuilder(HipChatNotifier notifier, AbstractBuild build) {
            this.notifier = notifier;
            this.message = new StringBuffer();
            this.build = build;
            startMessage();
        }

        public MessageBuilder appendStatusMessage() {
            message.append(getStatusMessage(build));
            return this;
        }
        
        static String getStatusMessage(AbstractBuild r) {
            if (r.isBuilding()) {
                return "Starting...";
            }
            
            Result result = r.getResult();
            Run previousBuild = r.getProject().getLastBuild().getPreviousBuild();
            Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
            if (result == Result.SUCCESS && previousResult == Result.FAILURE) return "Back to normal";
            if (result == Result.SUCCESS) return "Success - ";
            if (result == Result.FAILURE) return "<b>FAILURE </b>";
            if (result == Result.ABORTED) return "ABORTED";
            if (result == Result.NOT_BUILT) return "Not built";
            if (result == Result.UNSTABLE) return "Unstable";
            return "Unknown";
        }

        public MessageBuilder appendDetails()
        {
            message.append( "<br/>");
            message.append( build.getAction(CauseAction.class).getShortDescription() );
            
            if (getChanges(build)!= null)
            {
                message.append( "<br/>");
                message.append( getChanges(build) );
            }
            
            return this;
        }
        public MessageBuilder append(String string) {
            message.append(string);
            return this;
        }

        public MessageBuilder append(Object string) {
            message.append(string.toString());
            return this;
        }

        private MessageBuilder startMessage() {
            message.append(build.getProject().getDisplayName());
            message.append(" - ");
            message.append(build.getDisplayName());
            message.append(" ");
            return this;
        }

        public MessageBuilder appendOpenLink() {
            String url = notifier.getBuildServerUrl() + build.getUrl();
            message.append(" (<a href='").append(url).append("'>Open</a>)");
            return this;
        }

        public MessageBuilder appendDuration() {
            message.append(" after ");
            message.append(build.getDurationString());
            return this;
        }

        public String toString() {
            return message.toString();
        }
    }
}
