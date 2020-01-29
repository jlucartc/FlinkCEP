package FlinkCEPClasses

import java.sql.Timestamp

import PostgresConnector.PostgreSink
import org.apache.flink.api.java.io.TextInputFormat
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.cep.PatternSelectFunction
import org.apache.flink.cep.pattern.conditions.SimpleCondition
import org.apache.flink.cep.scala.pattern.Pattern
import org.apache.flink.core.fs.{FileSystem, Path}
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.cep.scala.{CEP, PatternStream}
import org.apache.flink.streaming.api.functions.source.FileProcessingMode
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import java.util.Properties

import org.apache.flink.api.common.ExecutionConfig
import org.slf4j.{Logger, LoggerFactory}

class FlinkCEPPipeline {

  /*
    BelongToPolygon vai capturar cada ponto novo e processá-lo em relação aos poligonos
    capturados até o momento.
    
    A pipeline vai ler todas as entradas novas. Se a entrada for um ponto, ele será processado.
    Se for um polígono, ele será adicionado ao state e cada ponto futuro será checado contra ele.
    Os eventos de polígono podem ser de adição ou remoção.
    
    A infraestrutura será a seguinte: existirão brokers kafka responsáveis pelo input e output dos dados.
    Os dispositivos irão enviar os dados para a nuvem diretamente pela internet ou LoRa + internet.
    Um serviço será responsável por extrair os dados da nuvem e alimentar uma fila no Kafka com eles.
    
    Essa fila originará o stream de entrada. Esse stream será lido por diferentes pipelines, realizando atividades
    distintas.
    
    Os possíveis pipelines serão: processamento de posição de veículos, emissão de eventos de entrada/saída de regiões,
    persistência dos dados recebidos para análise posterior
    
    - Procesamento de posição de veículos: responsável por receber os dados brutos e retirar apenas as informações da
      posição do veículo para uso em outras aplicações
      
    - Emissão de eventos de entrada/saída de regiões: responsável por emitir avisos quando veículos entrarem ou saírem
       de determinada região. Esses avisos irão alimentar uma fila de saída, que será lida por outras aplicações.
       
    - Persistência dos dados recebidos para análise posterior: responsável por capturar todos os dados enviados para a
      fila e persistí-los em algum lugar para que sejam analizados posteriormente ou para que possam reproduzir
      eventos passados.
      
     Posteriormente outros tipos de comparações podem ser feitas. Uma sugestão é o envio de tuplas (x,y,r), onde
     x,y são as coordenadas de um ponto e r é um raio partindo do ponto. Essa tupla representa uma região circular
     em torno de um ponto e pode ser útil para estabelescer critérios de proximidade de pontos específicos, como paradas
     de ônibus. Isso seria muito mais eficiente do que utilizar um polígono para a mesma tarefa, pois a relação entre a
     tupla e o cálculo de distância entre dois pontos é imediata.
     
   */
  val LOG: Logger = LoggerFactory.getLogger(classOf[FlinkCEPPipeline])
  LOG.info("\n\nIniciando Pipeline...\n\n")
  
  var env : StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment

  env.enableCheckpointing(10)
  //env.getCheckpointConfig.setMinPauseBetweenCheckpoints(500)
  env.setStreamTimeCharacteristic(TimeCharacteristic.ProcessingTime)
  env.setParallelism(1)

  //var input : DataStream[String] = env.readFile(new TextInputFormat(new Path("/home/luca/Desktop/lines")),"/home/luca/Desktop/lines",FileProcessingMode.PROCESS_CONTINUOUSLY,1)

  var input : DataStream[String] = env.readTextFile("/home/luca/Desktop/lines").name("Stream original")
  
  var tupleStream : DataStream[(String,Timestamp,Double,Double)] = input.map(new S2PlacaMapFunction()).name("Tuple Stream")
  
  var properties : Properties = new Properties()
  
  properties.setProperty("driver","org.postgresql.Driver")
  properties.setProperty("url","jdbc:postgresql://localhost:5432/mydb")
  properties.setProperty("user","luca")
  properties.setProperty("password","root")
  
  tupleStream.addSink(new PostgreSink(properties,env.getConfig)).name("Postgres Sink").setParallelism(1)
  tupleStream.writeAsText("/home/luca/Desktop/output",FileSystem.WriteMode.OVERWRITE).name("Output File Sink").setParallelism(1)

  env.execute()


}
