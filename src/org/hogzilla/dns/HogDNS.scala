/*
* Copyright (C) 2015-2015 Paulo Angelo Alves Resende <pa@pauloangelo.com>
*
* This program is free software; you can redistribute it and/or modify
* it under the terms of the GNU General Public License Version 2 as
* published by the Free Software Foundation.  You may not use, modify or
* distribute this program under any other version of the GNU General
* Public License.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program; if not, write to the Free Software
* Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/
/** 
 *  REFERENCES:
 *   - http://www.zytrax.com/books/dns/ch15/
 *   - http://ids-hogzilla.org/xxx/826000001
 */


package org.hogzilla.dns


import scala.collection.mutable.HashMap
import scala.collection.mutable.Map
import scala.collection.mutable.HashSet
import scala.math.random
import org.apache.hadoop.hbase.util.Bytes
import org.apache.spark._
import org.apache.spark.mllib.clustering.KMeans
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.rdd.RDD
import org.hogzilla.hbase.HogHBaseRDD
import org.hogzilla.event.{HogEvent, HogSignature}

import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.classification.SVMWithSGD
import scala.tools.nsc.doc.base.comment.OrderedList
import org.apache.spark.mllib.optimization.L1Updater
import org.hogzilla.util.HogFlow

/**
 * 
 */
object HogDNS {

  val signature = (HogSignature(3,"HZ: Suspicious DNS flow identified by K-Means clustering",2,1,826000001,826).saveHBase(),
                   HogSignature(3,"HZ: Suspicious DNS flow identified by SuperBag",2,1,826000002,826).saveHBase())
                   
  val numberOfClusters=9
  val maxAnomalousClusterProportion=0.05
  val minDirtyProportion=0.001
  
  /**
   * 
   * 
   * 
   */
  def run(HogRDD: RDD[(org.apache.hadoop.hbase.io.ImmutableBytesWritable,org.apache.hadoop.hbase.client.Result)],spark:SparkContext)
  {
    
    // DNS K-means clustering on flow bytes
    //kmeansBytes(HogRDD)
    
    // DNS Super Bag
    //superbag(HogRDD,spark)
    
    // DNS K-means clustering
   kmeans(HogRDD)
 
  }
  
  
  /**
   * 
   * 
   * 
   */
  def kmeansPopulate(event:HogEvent):HogEvent =
  {
    val centroids:String = event.data.get("centroids")
    val vector:String = event.data.get("vector")
    val clusterLabel:String = event.data.get("clusterLabel")
    val hostname:String = event.data.get("hostname")
    
    
    event.text = "This flow was detected by Hogzilla as an abnormal activity. In what follows you can see more information.\n"+
                 "Hostname mentioned in DNS flow: "+hostname+"\n"+
                 "Hogzilla module: HogDNS, Method: k-means clustering with k="+numberOfClusters+"\n"+
                 "URL for more information: http://ids-hogzilla.org/signature-db/"+"%.0f".format(signature._1.signature_id)+"\n"+""
                // "Centroids:\n"+centroids+"\n"+
                // "Vector: "+vector+"\n"+
                // "(cluster,label nDPI): "+clusterLabel+"\n"
    
    event.signature_id = signature._1.signature_id
                 
    event
  }
  
  
  /**
   * 
   * 
   * 
   */
  def kmeans(HogRDD: RDD[(org.apache.hadoop.hbase.io.ImmutableBytesWritable,org.apache.hadoop.hbase.client.Result)])
  {
    
	  val features = Array("flow:avg_packet_size",
			                   "flow:packets_without_payload",
                         "flow:avg_inter_time",
			                   "flow:flow_duration",
			                   "flow:max_packet_size",
			                   "flow:bytes",
			                   "flow:packets",
			                   "flow:min_packet_size",
			                   "flow:packet_size-0",
			                   "flow:inter_time-0",
                         "flow:packet_size-1",
		                     "flow:dns_num_queries",
			                   "flow:dns_num_answers",
			                   "flow:dns_ret_code",
			                   "flow:dns_bad_packet",
		                 	   "flow:dns_query_type",
		                 	   "flow:dns_rsp_type")
 
    println("Filtering HogRDD...")
    val DnsRDD = HogRDD.
        map { case (id,result) => {
          val map: Map[String,String] = new HashMap[String,String]
              map.put("flow:id",Bytes.toString(id.get).toString())
              HogHBaseRDD.columns.foreach { column => 
                
                val ret = result.getValue(Bytes.toBytes(column.name.split(":")(0).toString()),Bytes.toBytes(column.name.split(":")(1).toString()))
                map.put(column.name, Bytes.toString(ret)) 
        }
          if(map.get("flow:dns_num_queries")==null) map.put("flow:dns_num_queries","0")
          if(map.get("flow:dns_num_answers")==null) map.put("flow:dns_num_answers","0")
          if(map.get("flow:dns_ret_code")==null) map.put("flow:dns_ret_code","0")
          if(map.get("flow:dns_bad_packet")==null) map.put("flow:dns_bad_packet","0")
          if(map.get("flow:dns_query_type")==null) map.put("flow:dns_query_type","0")
          if(map.get("flow:dns_rsp_type")==null) map.put("flow:dns_rsp_type","0")
          if(map.get("flow:packet_size-1")==null) map.put("flow:packet_size-1","0")
          
          val lower_ip = result.getValue(Bytes.toBytes("flow"),Bytes.toBytes("lower_ip"))
          val upper_ip = result.getValue(Bytes.toBytes("flow"),Bytes.toBytes("upper_ip"))
          new HogFlow(map,Bytes.toString(lower_ip),Bytes.toString(upper_ip))
        }
    }.filter(x =>  ( x.get("flow:lower_port").equals("53") ||
                     x.get("flow:upper_port").equals("53")
                   ) && x.get("flow:packets").toDouble.>(1)
                     && x.get("flow:id").split('.')(0).toLong.<(System.currentTimeMillis()-6000000)
             ).cache

  println("Counting HogRDD...")
  val RDDtotalSize= DnsRDD.count()
  println("Filtered HogRDD has "+RDDtotalSize+" rows!")
  
  if(RDDtotalSize == 0)
    return
  
  println("Calculating some variables to normalize data...")
  val DnsRDDcount = DnsRDD.map(flow => features.map { feature => flow.get(feature).toDouble }).cache()
  val n = RDDtotalSize
  val numCols = DnsRDDcount.first.length
  val sums = DnsRDDcount.reduce((a,b) => a.zip(b).map(t => t._1 + t._2))
  val sumSquares = DnsRDDcount.fold(
      new Array[Double](numCols)
  )(
      (a,b) => a.zip(b).map(t => t._1 + t._2*t._2)
   )
      
  val stdevs = sumSquares.zip(sums).map{
      case(sumSq,sum) => math.sqrt(n*sumSq - sum*sum)/n
    }
    
  val means = sums.map(_/n)
      
  def normalize(vector: Vector):Vector = {
    val normArray = (vector.toArray,means,stdevs).zipped.map(
        (value,mean,std) =>
          if(std<=0) (value-mean) else (value-mean)/std)
    return Vectors.dense(normArray)
  }
    
  println("Normalizing data...")
    val labelAndData = DnsRDD.map { flow => 
     val vector = Vectors.dense(features.map { feature => flow.get(feature).toDouble })
       // ( (DNS,1,lele.com,flow) , vector )
       ((flow.get("flow:detected_protocol"), 
          if (flow.get("event:priority_id")!=null && flow.get("event:priority_id").equals("1")) 1 else 0 , 
          flow.get("flow:host_server_name"),flow),normalize(vector)
       )
    }
  
    println("Estimating model...")
    val data = labelAndData.values.cache()
    val kmeans = new KMeans()
    kmeans.setK(numberOfClusters)
    val vectorCount = data.count()
    println("Number of vectors: "+vectorCount)
    val model = kmeans.run(data)
    
    println("Predicting points (ie, find cluster for each point)...")
     val clusterLabel = labelAndData.map({
      case (label,datum) =>
        val cluster = model.predict(datum)
        (cluster,label,datum)
    })
    
    println("Generating histogram...")
    val clusterLabelCount = clusterLabel.map({
      case (cluster,label,datum) =>
        val map: Map[(Int,String),(Double,Int)] = new HashMap[(Int,String),(Double,Int)]
        map.put((cluster,label._1),  (label._2.toDouble,1))
        map
    }).reduce((a,b) => { 
      
        b./:(0){
         case (c,((key:(Int,String)),(avg2,count2))) =>
           
                val avg = (a.get(key).get._1*a.get(key).get._2 + b.get(key).get._1*b.get(key).get._2)/
                          (a.get(key).get._2+b.get(key).get._2)
                          
                a.put(key, (avg,a.get(key).get._2+b.get(key).get._2))
           
               0
              }
      /*  
      b.keySet().toArray()
      .map { 
        case key: (Int,String) =>  
            if (a.containsKey(key))
            {
              val avg = (a.get(key)._1*a.get(key)._2 + b.get(key)._1*b.get(key)._2)/
                          (a.get(key)._2+b.get(key)._2)
                          
              a.put(key, (avg,a.get(key)._2+b.get(key)._2))
            }else
              a.put(key,b.get(key))
      }
      */
      a
    })
    
    println("######################################################################################")
    println("######################################################################################")
    println("######################################################################################")
    println("######################################################################################")
    println("DNS K-Means Clustering")
    println("Centroids")
    val centroids = ""+model.clusterCenters.mkString(",\n")
    //model.clusterCenters.foreach { center => centroids.concat("\n"+center.toString) }
    
    clusterLabelCount./:(0) 
     { case (z,(key:(Int,String),(avg,count))) =>    
      val cluster = key._1
      val label = key._2
      //val count =clusterLabelCount.get(key)._2.toString
      //val avg = clusterLabelCount.get(key)._1.toString
      println(f"Cluster: $cluster%1s\t\tLabel: $label%20s\t\tCount: $count%10s\t\tAvg: $avg%10s")
      0
      }

      val thr=maxAnomalousClusterProportion*RDDtotalSize
      
      
      println("Selecting cluster to be tainted...")
       val taintedArray = clusterLabelCount.filter({ case (key:(Int,String),(avg,count)) => 
                                                          (count.toDouble < thr 
                                                              && avg.toDouble >= minDirtyProportion )
                          }).map(_._1)
                //.
                //  sortBy ({ case (cluster:Int,label:String) => clusterLabelCount.get((cluster,label))._1.toDouble }).reverse
      
      taintedArray.par.map 
      {
        tainted =>
        
        //val tainted = taintedArray.apply(0)
                  
        println("######################################################################################")
        println("Tainted flows of: "+tainted.toString())
      
        println("Generating events into HBase...")
        clusterLabel.filter({ case (cluster,(group,tagged,hostname,flow),datum) => (cluster,group).equals(tainted) && tagged.equals(0) }).
        foreach{ case (cluster,(group,tagged,hostname,flow),datum) => 
          val event = new HogEvent(flow)
          event.data.put("centroids", centroids)
          event.data.put("vector", datum.toString)
          event.data.put("clusterLabel", "("+cluster.toString()+","+group+")")
          event.data.put("hostname", flow.get("flow:host_server_name"))
          kmeansPopulate(event).alert()
        }

   /*   
     (1 to 9).map{ k => 
        println("######################################################################################")
        println(f"Hosts from cluster $k%1s")
        clusterLabel.filter(_._1.equals(k)).foreach{ case (cluster,label,datum) => 
          print(label._3+"|")      
        }
        println("")
     }
*/
      println("######################################################################################")
      println("######################################################################################")
      println("######################################################################################")
      println("######################################################################################")           

   }
      
   if(taintedArray.isEmpty)
   {
        println("No flow matched!")
   }

  }
  
  
  /**
   * 
   * 
   * 
   */
  def superbag(HogRDD: RDD[(org.apache.hadoop.hbase.io.ImmutableBytesWritable,org.apache.hadoop.hbase.client.Result)],spark:SparkContext)
  {

    class flowSet(flowc:Map[String,String]) extends Serializable
    {
      val flows=new HashMap[(String,String,String),(HashSet[Map[String,String]],HashMap[String,Double],LabeledPoint)]
      // (flow:lip,flow:uip,flow:host) -> (Set[flows],Info,LabeledPoint)
      
      add(flowc)

       def add(flow:Map[String,String]) 
      {
        // Add flow in the Set
        val value = flows.get((flow.get("flow:lower_name").get,flow.get("flow:upper_name").get,flow.get("flow:host_server_name").get))
        
        if(value == null)
        {
          val a = new HashSet[Map[String,String]]
          val b = new HashMap[String,Double]
          a.add(flow)
          flows.put((flow.get("flow:lower_name").get,flow.get("flow:upper_name").get,flow.get("flow:host_server_name").get), 
                    (a,b,new LabeledPoint(0,Vectors.dense(0))))
        }else
        {
          value.get._1.add(flow)
        }
      } 
      
      def merge(flowset:flowSet):flowSet =
      {
        val iter = flowset.flows.keySet.map({ case key:(String,String,String) => 
                      val value = this.flows.get(key)
                      if( value == null)
                      {
                           val a = new HashSet[Map[String,String]]
                           val b = new HashMap[String,Double]
                           a.++(flowset.flows.get(key).get._1)
                           this.flows.put(key, (a,b,new LabeledPoint(0,Vectors.dense(0)) ))
                      }else
                      {
                        this.flows.get(key).get._1.++(flowset.flows.get(key).get._1)
                      }
                   })
          this
          /*
          val sum = this.flows.get(key)._2.get("qtd") + flowset.flows.get(key)._2.get("qtd")
          this.flows.get(key)._2.put("avg",
              (this.flows.get(key)._2.get("avg")*this.flows.get(key)._2.get("qtd")
                  +
              flowset.flows.get(key)._2.get("avg")*flowset.flows.get(key)._2.get("qtd"))/
              sum
          )
          
          this.flows.get(key)._2.put("qtd",sum)
        */
      }
    }
    
    // Populate flowSet
    val DnsRDD = HogRDD.
        map { case (id,result) => {
          val map: Map[String,String] = new HashMap[String,String]
              map.put("flow:id",Bytes.toString(id.get).toString())
              HogHBaseRDD.columns.foreach { column => 
                
                val ret = result.getValue(Bytes.toBytes(column.name.split(":")(0).toString()),Bytes.toBytes(column.name.split(":")(1).toString()))
                map.put(column.name, Bytes.toString(ret)) 
        }
          if(map.get("flow:dns_num_queries")==null) map.put("flow:dns_num_queries","0")
          if(map.get("flow:dns_num_answers")==null) map.put("flow:dns_num_answers","0")
          if(map.get("flow:dns_ret_code")==null) map.put("flow:dns_ret_code","0")
          if(map.get("flow:dns_bad_packet")==null) map.put("flow:dns_bad_packet","0")
          if(map.get("flow:dns_query_type")==null) map.put("flow:dns_query_type","0")
          if(map.get("flow:dns_rsp_type")==null) map.put("flow:dns_rsp_type","0")
        map
        }
    }.filter(x => x.get("flow:lower_port").equals("53") && x.get("flow:packets").get.toDouble.>(1)).cache
      
    val superBag = DnsRDD.map({flow => new flowSet(flow) }).reduce((a,b) => a.merge(b)) 

    
/*
         * Commented after java.Map -> scala.Map
             
    // compute sizes, inter times, means and stddevs
    val labeledpoints:HashSet[LabeledPoint] = new HashSet()
    superBag.flows.keySet.map({ key =>  superBag.flows.get(key).get}).map({case (flowSet1:HashSet[Map[String,String]],info:HashMap[String,Double],labeledpoint:LabeledPoint) => 
      
        if(flowSet1.size>4)
        {
      
        val flowSetOrdered = flowSet1.map({  case f:Map[String,String] => f })
                                    // .sortBy(f => f.get("flow:first_seen").get)
                                    // .toArray
                
        val timeArray:Array[Double] = Array.fill[Double](flowSetOrdered.size)(0)
        var dirty:Double =0;
        var clean:Double =0;
  
    
         
        (0 to (flowSetOrdered.size-2)).map({ k => 
             timeArray.update(k, flowSetOrdered(k+1).get("flow:first_seen").get.toDouble - flowSetOrdered(k).get("flow:first_seen").toDouble)
             
             
             if (flowSetOrdered(k).get("event:priority_id")!=null)
             {
               //println(flowSetOrdered(k).get("event:priority_id"))
                dirty=1;
             }
             
             
             if (flowSetOrdered(k).get("flow:detected_protocol").ne("5/DNS"))
             {
                clean=1;
             }
        })
        
        if(dirty==1 || clean==1)
        {
          val count = timeArray.length
          val avg = timeArray.sum/count
          val devs = timeArray.toSeq.map { t => (t-avg)*(t-avg) }
          val stddev = Math.sqrt(devs.sum/count)
        
          info.put("flow_intertime_avg", avg)
          info.put("flow_intertime_stddev", stddev)
          info.put("flow_intertime_count", count)
          
          if(dirty==1)
          {
            println(LabeledPoint(1,Vectors.dense(count,avg,stddev)).toString)
            //labeledpoints.add(LabeledPoint(1,Vectors.dense(count,avg,stddev)))
            info.put("dirty", 1)
            labeledpoints.add(LabeledPoint(1,Vectors.dense(stddev)))
          }else{
            println(LabeledPoint(0,Vectors.dense(count,avg,stddev)).toString)
            info.put("dirty", 0)
            //labeledpoints.add(LabeledPoint(0,Vectors.dense(count,avg,stddev)))
            labeledpoints.add(LabeledPoint(0,Vectors.dense(stddev)))
          }
        }
        
        }
    })
   
  
    val arraylabeledpoints = labeledpoints.toArray().map({ case xy:LabeledPoint => xy })
   
    // SVM considering dirty flows from Snort
    
    val svmAlg = new SVMWithSGD()
    svmAlg.optimizer.setNumIterations(200).setRegParam(0.1).setUpdater(new L1Updater)
    
    val model = svmAlg.run(spark.parallelize(arraylabeledpoints).cache)
    
    model.clearThreshold()
    
   println("######################################################################################")           
   
   println("Model.intercept: "+model.intercept+" Model.weights: "+model.weights)
   
    superBag.flows.keySet.map({ case key:(String,String,String) =>  
    
        if(superBag.flows.get(key).get._1.size()>4)
        {
      
      val info = superBag.flows.get(key).get._2
      
     // val score = model.predict(Vectors.dense(info.get("flow_intertime_count"),info.get("flow_intertime_avg"),info.get("flow_intertime_stddev")) )
      val score = model.predict(Vectors.dense(info.get("flow_intertime_stddev").get) )
    // print(score+"|")
     
     if(info.get("flow_intertime_avg").get<100000 && info.get("dirty")!=null)
     println("USED: "+key._1+" <-> "+key._2+", hostname: "+key._3+" ("+info.get("flow_intertime_count").toString+","+info.get("flow_intertime_avg").toString+","+info.get("flow_intertime_stddev").toString+")")
            
     if(score>0)
     {
     //  println("Tainted flow: "+key._1+" <-> "+key._2+", hostname: "+key._3+"")
       println("Tainted flow: "+key._1+" <-> "+key._2+", hostname: "+key._3+" ("+info.get("flow_intertime_count").toString+","+info.get("flow_intertime_avg").toString+","+info.get("flow_intertime_stddev").toString+")")
     }
        }
     })
     
    println("######################################################################################")           
 
    // Taint the dirty side, generating HogEvents
*/
     
  }
  
}