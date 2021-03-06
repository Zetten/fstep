FROM centos:7

RUN sed -i 's/enabled=.*$/enabled=0/' /etc/yum/pluginconf.d/fastestmirror.conf

RUN yum install -y\
 epel-release\
 && yum clean all

# Update and install core dependencies and tools
RUN yum update -y && yum install -y\
 autoconf\
 binutils\
 bison\
 createrepo\
 curl-devel\
 fcgi-devel\
 flex-devel\
 flex\
 gcc-c++\
 gcc\
 gdal-devel\
 geos-devel\
 git\
 java-1.8.0-openjdk-devel\
 java-1.8.0-openjdk\
 libstdc++-devel\
 libuuid-devel\
 libxml2-devel\
 libxslt-devel\
 make\
 openssh-server\
 openssl-devel\
 proj-devel\
 python-devel\
 redhat-rpm-config\
 rpm-build\
 sudo\
 tar\
 unzip\
 wget\
 which\
 zip\
 && yum clean all
ENV JAVA_HOME=/usr/lib/jvm/java-1.8.0

# Install puppet for testing distribution module
RUN yum install -y \
 https://yum.puppetlabs.com/puppetlabs-release-pc1-el-7.noarch.rpm \
 && yum clean all
RUN yum install -y \
 puppet-agent \
 && yum clean all
ENV PATH=/opt/puppetlabs/bin:${PATH}

# Gradle
ENV GRADLE_VERSION 4.0.1
RUN cd /opt && curl -sL https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip >/tmp/gradle.zip && unzip -q /tmp/gradle.zip
ENV GRADLE_HOME /opt/gradle-${GRADLE_VERSION}
ENV PATH=${GRADLE_HOME}/bin:${PATH}

# Set up user and environment for interactive usage
RUN adduser -d /home/fstep -m fstep && echo "fstep" | passwd fstep --stdin
RUN ssh-keygen -t rsa -b 4096 -P "" -f /etc/ssh/ssh_host_rsa_key
RUN echo -e "export PATH=$PATH\nexport LD_LIBRARY_PATH=$LD_LIBRARY_PATH\nexport PERL5LIB=$PERL5LIB\nexport PYTHONPATH=$PYTHONPATH\nexport M2_HOME=$M2_HOME\nexport JAVA_HOME=$JAVA_HOME" >>/etc/profile.d/dockerenv.sh
RUN echo -e "fstep ALL=(ALL) NOPASSWD: ALL" >>/etc/sudoers.d/01_fstep

# The script allows the uid of the fstep user to be modified prior to launching sshd
ADD sshdBootstrap.sh /usr/sbin/sshdBootstrap.sh
