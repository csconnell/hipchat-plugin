package jenkins.plugins.hipchat;

public interface HipChatService {
    void publish(String message);

    void publish(String message, String color);
    
    void publishText(String message, String color);
}
