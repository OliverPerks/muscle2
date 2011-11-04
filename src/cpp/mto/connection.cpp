#include "connection.hpp"

/* from local */
Connection::Connection(tcp::socket* s, char* requestBuffer)
   : sock(s), reqBuf(requestBuffer), closing(false), hasRemotePeer(false), referenceCount(0), secondMto(0)
{
}

/* from remote */
Connection::Connection(Header h, tcp::socket* s, PeerConnectionHandler * t)
  : sock(s), header(h), closing(false), hasRemotePeer(true), referenceCount(0), reqBuf(0) ,
    secondMto(t)
{
  remoteConnections[Identifier(h)]=this;
  
  header.type=Header::Data;

  ++referenceCount;
  localToRemoteR(error_code(),0);
}

void Connection::readRequest(const boost::system::error_code& e, size_t )
{
  if(e)
  {
    Logger::error(Logger::MsgType_ClientConn, "Error reading request from %s:%d (%s)",
                  sock->remote_endpoint().address().to_string().c_str(),
                  sock->remote_endpoint().port(),
                  e.message().c_str()
                 );
    delete [] reqBuf;
    clean();
    return;
  }
  
  Request request = Request::deserialize(reqBuf);
  header = Header(request);
  delete [] reqBuf;
  
  unsigned short localPortLow = Options::getInstance().getLocalPortLow();
  unsigned short localPortHigh = Options::getInstance().getLocalPortHigh();
  
  switch(request.type){
    case Request::Register:
    {
      if(request.srcPort<localPortLow || request.srcPort>localPortHigh)
      {
        Logger::error(Logger::MsgType_ClientConn, "Port %s:%hu out of range - registering port aborted (connection from %s:%hu)",
                      ip::address_v4(request.srcAddress).to_string().c_str(),
                      request.srcPort,
                      sock->remote_endpoint().address().to_string().c_str(),
                      sock->remote_endpoint().port()
                     );
        clean();
        return;
      }
      availablePorts.insert(pair<unsigned long, unsigned short>(request.srcAddress,request.srcPort));
      
      Logger::info(Logger::MsgType_ClientConn, "Listening port registered: %s:%hu",
                   ip::address_v4(request.srcAddress).to_string().c_str(),
                   request.srcPort
      );
      
      clean();
      break;
    }
    case Request::Connect:
    {
      if(request.dstPort>=localPortLow && request.dstPort<=localPortHigh)
      { // local to local
        Logger::error(Logger::MsgType_ClientConn, "Requested connection to local port range - to %s:%hu from %s:%hu",
                  ip::address_v4(request.dstAddress).to_string().c_str(),
                  request.dstPort,
                  sock->remote_endpoint().address().to_string().c_str(),
                  sock->remote_endpoint().port()
        );
        clean();
        return;
      }
      else
      { // local to remote
        secondMto = getPeer(request.dstPort);
        if(!secondMto)
        {
          Logger::error(Logger::MsgType_ClientConn, "Requested connection to port out of range %s:%hu from %s:%hu",
                   ip::address_v4(request.dstAddress).to_string().c_str(),
                   request.dstPort,
                   sock->remote_endpoint().address().to_string().c_str(),
                   sock->remote_endpoint().port()
          );
          clean();
          break;
        }
        
        Header h(request);
        reqBuf = new char[Header::getSize()];
        h.serialize(reqBuf);
        
        remoteConnections[Identifier(h)]=this;
        
        ++referenceCount;
        async_write(*(secondMto->getSocket()), buffer(reqBuf, Header::getSize()), transfer_all(), 
                    bind(&Connection::connectRemoteRequestErrorMonitor, this, placeholders::error, placeholders::bytes_transferred));
        
        Logger::trace(Logger::MsgType_ClientConn, "Requesting connection to host %s:%hu from peer %s:%hu",
                      ip::address_v4(request.dstAddress).to_string().c_str(),
                      request.dstPort,
                      secondMto->getSocket()->remote_endpoint().address().to_string().c_str(),
                      secondMto->getSocket()->remote_endpoint().port()
        );
      }
      break;
    }
    default:
    {
      Logger::error(Logger::MsgType_ClientConn, "Client from %s:%hu sent unknown message (type %hhd)",
                    sock->remote_endpoint().address().to_string().c_str(),
                    sock->remote_endpoint().port(),
                    request.type
      );
      clean();
    }
  }
}

void Connection::clean()
{
  if(!closing)
  {
    Logger::trace(Logger::MsgType_ClientConn, "Closing connection between %s:%hu and %s:%hu",
                  ip::address_v4(header.dstAddress).to_string().c_str(),
                  header.dstPort,
                  ip::address_v4(header.srcAddress).to_string().c_str(),
                  header.srcPort
    );
  }
  closing = true;
  if(hasRemotePeer)
  {
   hasRemotePeer=false;
   header.type=Header::Close;
   char * data = new char[Header::getSize()];
   header.serialize(data);
   if(secondMto)
     async_write(*(secondMto->getSocket()), buffer(data, Header::getSize()), transfer_all(), Bufferfreeer(data, 0));
  }
  
  remoteConnections.erase(Identifier(header));
  if(sock->is_open())
    sock->close();
  if(!referenceCount)
    delete sock;
 
  
  if(!referenceCount)
  {
    Logger::debug(Logger::MsgType_ClientConn, "Closed connection between %s:%hu and %s:%hu",
                  ip::address_v4(header.dstAddress).to_string().c_str(),
                  header.dstPort,
                  ip::address_v4(header.srcAddress).to_string().c_str(),
                  header.srcPort
    );
    delete this;
  }
    
}

void Connection::remoteClosed()
{
  hasRemotePeer = false;
  clean();
}


void Connection::error(const boost::system::error_code& e)
{
  if(closing)
  { 
    clean(); 
    return;
  }

  if(e!=asio::error::eof)
    Logger::error(Logger::MsgType_ClientConn, "Error ocurred in connection between %s:%hu and %s:%hu (%s)",
                  ip::address_v4(header.dstAddress).to_string().c_str(),
                  header.dstPort,
                  ip::address_v4(header.srcAddress).to_string().c_str(),
                  header.srcPort,
                  e.message().c_str()
    );
  
  error_code ec;
  
  if(sock->is_open())
      sock->shutdown(boost::asio::socket_base::shutdown_both, ec);

  if(ec)
    Logger::trace(Logger::MsgType_ClientConn, "Could not shut down first socket - eror: %s", ec.message().c_str() );
    
  clean();
}

void Connection::connectRemoteRequestErrorMonitor(const error_code& e, size_t count)
{
  referenceCount--;
  delete [] reqBuf;
  if(closing) { clean(); return;}
  
  if(e)
  {
    secondMto->errorOcured(e, "ConnectionMonitor: Write failed");
    clean();
  }
}

void Connection::localToRemoteR(const error_code& e, size_t)
{
  referenceCount--;
  if(closing) { clean(); return;};
  if(e)
  {
    secondMto->errorOcured(e, "localToRemoteR: Write failed");
    clean();
    delete [] reqBuf;
    return;
  }
  
  delete [] reqBuf;
  
  referenceCount++;
  sock->async_read_some(buffer(receiveBuffer), bind(&Connection::localToRemoteW, this, placeholders::error, placeholders::bytes_transferred));
}

void Connection::localToRemoteW(const error_code& e, size_t count)
{
  if(e)
  {
    referenceCount--;
    error(e);
    return;
  }
  reqBuf = new char[count + Header::getSize()];
  header.length=count;
  header.serialize(reqBuf);
  memcpy(reqBuf+Header::getSize(), receiveBuffer.c_array(), count);
  async_write(*(secondMto->getSocket()), buffer(reqBuf, count + Header::getSize()), transfer_all(),
              bind(&Connection::localToRemoteR, this, placeholders::error, placeholders::bytes_transferred));
  
}

void Connection::remoteToLocal(char * data, int length )
{
  referenceCount++;
  async_write(*sock, buffer(data, length), Bufferfreeer(data, this));
}

void Connection::connectedRemote(Header h)
{
  reqBuf = new char[Header::getSize()];
  h.serialize(reqBuf);
  referenceCount++;
  async_write(*sock, buffer(reqBuf,Header::getSize()), transfer_all(), Bufferfreeer(reqBuf,this));
  reqBuf = 0;
  
  if(h.length)
  { // Fail
    Logger::debug(Logger::MsgType_ClientConn, "Got negative response for connection request (%s:%hu -- %s:%hu)", 
                  ip::address_v4(header.dstAddress).to_string().c_str(),
                  header.dstPort,
                  ip::address_v4(header.srcAddress).to_string().c_str(),
                  header.srcPort
                 );
    clean();
  }
  else
  { // Success
    Logger::debug(Logger::MsgType_ClientConn, "Remote connection succeeded (%s:%hu -- %s:%hu)", 
                  ip::address_v4(header.dstAddress).to_string().c_str(),
                  header.dstPort,
                  ip::address_v4(header.srcAddress).to_string().c_str(),
                  header.srcPort
                 );
    header.type=Header::Data;
    hasRemotePeer = true;
    referenceCount++;
    localToRemoteR(error_code(), 0);
  }
}

void Connection::peerDied(PeerConnectionHandler* handler)
{
  if(secondMto != handler)
    return;
  secondMto = 0;
  clean();
}

void Connection::Bufferfreeer::operator ()(const error_code& e, size_t)
{
  delete data;
  if(thiz){
    thiz->referenceCount--;
    if(thiz->closing) { thiz->clean(); return;}
    if(e)
      thiz->error(e);
  }
};

