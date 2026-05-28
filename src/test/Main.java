import com.kyon.llmgateway.adapter.NvidiaAdapter;
import com.kyon.llmgateway.adapter.OpenRouterAdapter;

import java.io.IOException;

public static void main(String[] args) throws IOException, InterruptedException {
    //OpenRouterAdapter adapter = new OpenRouterAdapter();
    NvidiaAdapter adapter = new NvidiaAdapter();

    String res = adapter.chat("你好");
    System.out.printf("结果是：%s", res);


}
